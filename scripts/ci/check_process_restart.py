#!/usr/bin/env python3
"""Hosted-only, fail-closed force-stop protocol. Never print device output or arguments."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

CLASS = 'com.fsm.restart.ProcessRestartTest'
COMPONENT = 'com.fsm.test/androidx.test.runner.AndroidJUnitRunner'
UUID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}')
HASH = re.compile(r'[0-9a-f]{64}')
OUT = Path('android/app/build/outputs/process-restart')
FLAGS = ('baseline21Passed', 'singleHostedEmulator', 'emptyAccountPreflight',
         'offlineBeforeSeed', 'seedRunnerPassed', 'seedProofPassed',
         'seedPidAliveAfterFinish', 'forceStopAfterSeed', 'processAbsentAfterStop',
         'offlineBeforeVerify', 'freshVerifyPid', 'verifyRunnerPassed',
         'verifyProofPassed', 'verifyPidAliveAfterFinish', 'manifestUnchanged',
         'offlineAfterVerify', 'passed')

BASELINE = {
    ('com.fsm.RoomTest', 'localCrudAndPendingLock'),
    ('com.fsm.RoomTest', 'transactionRollsBackFinalAndOutbox'),
    ('com.fsm.RoomTest', 'restartKeepsUuidFrozenBytesAndRecoversRunning'),
    ('com.fsm.RoomTest', 'uploadBeforeCloseDependenciesAndLocalPathRetention'),
    ('com.fsm.RoomTest', 'loggerBoundedAndWhitelisted'),
    ('com.fsm.RoomTest', 'orphanCleanupNeverDeletesRegisteredQueuedPhoto'),
    ('com.fsm.RoomTest', 'importCorrectsExifBeforeStripping'),
    ('com.fsm.RoomTest', 'importWithoutExifPreservesUprightPixelsAndFinalHash'),
    ('com.fsm.RoomTest', 'conflictTransitionBeforeCancelledFetchSurvivesRestart'),
    ('com.fsm.RoomTest', 'failedAuthorizedResolveNeverUsesStaleShadowOrDiscardsQueue'),
    ('com.fsm.RoomTest', 'changedClosedCanonicalRequiresNewChoiceAndCannotReopenDraft'),
    ('com.fsm.RoomTest', 'atomicConflictRollsBackAndLegacyInterruptedStateRepairs'),
    ('com.fsm.RoomTest', 'unknownReceiptRetainsFrozenWireAndBackoffThenSameOperationAcks'),
    ('com.fsm.RoomTest', 'cancelAfterDispatchKeepsRetryableSameFrozenOperation'),
    ('com.fsm.RoomTest', 'missingPhotoIsDefinitiveBeforeDispatchAndMakesNoMutationRequest'),
    ('com.fsm.ui.LifecycleUiTest', 'loginLandscapeRetainsLoginAndKeepsPasswordEphemeralWithIme'),
    ('com.fsm.ui.LifecycleUiTest', 'listAndDetailStayReachableInLandscapeAndAfterRecreation'),
    ('com.fsm.ui.LifecycleUiTest', 'unsavedFormSurvivesRotationThenConfirmedSaveSurvivesNavigationAndRecreation'),
    ('com.fsm.ui.LifecycleUiTest', 'savingRemainsTrueUntilEveryQueuedLocalWriteFinishes'),
    ('com.fsm.ui.LifecycleUiTest', 'queuedRecreationKeepsLocksAndShowsLiveRoomAcknowledgement'),
    ('com.fsm.ui.LifecycleUiTest', 'recreatedUiObservesRoomRetryAndAuthErrorsWithoutRawCodes'),
}


def require(ok):
    if not ok:
        raise ValueError('Protocol requirement failed')


def exact(obj, keys):
    require(type(obj) is dict and set(obj) == set(keys.split()))


def integer(value, low=0, high=2**63-1):
    require(type(value) is int and low <= value <= high)


def identifier(value):
    require(type(value) is str and UUID.fullmatch(value) is not None)


def digest(value):
    require(type(value) is str and HASH.fullmatch(value) is not None)


def load_json(raw, limit):
    require(0 < len(raw) <= limit)
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result)
            result[key] = value
        return result
    def bad_constant(_):
        raise ValueError('Non-JSON number')
    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs, parse_constant=bad_constant)


def validate_manifest(raw, run):
    m = load_json(raw, 65536)
    exact(m, 'schemaVersion runId accountId draft queued photos operations')
    integer(m['schemaVersion'], 1, 1)
    require(m['runId'] == run == m['accountId'])
    identifier(run)
    ids = [run]
    for name in ('draft', 'queued'):
        row = m[name]
        exact(row, 'orderId resultId jsonSha256')
        ids += [row['orderId'], row['resultId']]
        digest(row['jsonSha256'])
    require(type(m['photos']) is list and len(m['photos']) == 2)
    photos = {}
    for row in m['photos']:
        exact(row, 'id orderId byteSize sha256 serverPhotoId contentPath')
        ids.append(row['id'])
        integer(row['byteSize'], 1, 10485760)
        digest(row['sha256'])
        require(row['orderId'] not in photos)
        photos[row['orderId']] = row
    require(set(photos) == {m['draft']['orderId'], m['queued']['orderId']})
    draft, queued = (photos[m[k]['orderId']] for k in ('draft', 'queued'))
    require(draft['serverPhotoId'] is None and draft['contentPath'] is None)
    identifier(queued['serverPhotoId'])
    ids.append(queued['serverPhotoId'])
    require(queued['contentPath'] == '/api/v1/photos/' + queued['serverPhotoId'] + '/content')
    require(type(m['operations']) is list and len(m['operations']) == 2)
    upload, close = m['operations']
    for row in m['operations']:
        exact(row, 'id kind orderId photoId sequence state payloadSha256 wireSha256 dependencies attempts nextAttemptAt error')
        ids.append(row['id'])
        require(row['orderId'] == m['queued']['orderId'] and row['error'] is None)
        digest(row['payloadSha256'])
        for key in ('sequence', 'attempts', 'nextAttemptAt'):
            integer(row[key])
    require(upload['kind'] == 'UPLOAD_PHOTO' and upload['state'] == 'ACKED')
    require(upload['photoId'] == queued['id'] and upload['attempts'] == 1)
    require(upload['wireSha256'] is None and upload['dependencies'] == '[]')
    require(upload['sequence'] == 1 and upload['nextAttemptAt'] == 0)
    require(close['kind'] == 'CLOSE_WORK_ORDER' and close['state'] == 'RUNNING')
    require(close['photoId'] is None and close['attempts'] == 3 and close['sequence'] == 2)
    digest(close['wireSha256'])
    require(close['nextAttemptAt'] > 0)
    require(close['dependencies'] == json.dumps([upload['id']], separators=(',', ':')))
    for value in ids:
        identifier(value)
    require(len(ids) == len(set(ids)))
    return m


def validate_result(raw, phase):
    m = load_json(raw, 4096)
    keys = ['passed', 'sameProcess']
    if phase == 'verify':
        keys += ['newProcess', 'startupRestored', 'draftDurable', 'queuedLocked',
                 'photosIntact', 'recoveredRunning', 'cleanupOwned']
    exact(m, 'schemaVersion phase ' + ' '.join(keys))
    integer(m['schemaVersion'], 1, 1)
    require(m['phase'] == phase and all(m[k] is True for k in keys))
    return m


def runner_passed(raw, method):
    text = raw.decode('utf-8')
    require(len(raw) <= 65536)
    statuses, current, finals = [], {}, []
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, sep, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            require(bool(sep) and key not in current)
            current[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = line.split(': ', 1)[1]
            statuses.append((code, current))
            current = {}
        elif line.startswith('INSTRUMENTATION_CODE: '):
            finals.append(line.split(': ', 1)[1])
        elif line.startswith(('INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED')):
            require(False)
        elif line.startswith('INSTRUMENTATION_RESULT: shortMsg='):
            require(False)
    require(not current and finals == ['-1'] and len(statuses) == 2)
    require([s[0] for s in statuses] == ['1', '0'])
    for _, fields in statuses:
        require(fields.get('class') == CLASS and fields.get('test') == method)
        require(fields.get('current') == '1' and fields.get('numtests') == '1')


def baseline_passed():
    files = list(Path('android/app/build/outputs/androidTest-results/connected').rglob('*.xml'))
    require(bool(files))
    cases = []
    for file in files:
        require(file.stat().st_size <= 4*1024*1024)
        root = ET.parse(file).getroot()
        for case in root.iter('testcase'):
            require(not any(case.find(k) is not None for k in ('failure', 'error', 'skipped')))
            cases.append((case.get('classname'), case.get('name')))
    require(len(cases) == 21 and len(set(cases)) == 21 and set(cases) == BASELINE)


def command(args, seconds=20, cap=1024*1024):
    # Raw subprocess output never reaches stdout, annotations, or artifacts.
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        result = subprocess.run(args, stdout=out, stderr=err, timeout=seconds, check=False)
        require(result.returncode == 0 and out.tell() <= cap and err.tell() <= cap)
        out.seek(0)
        return out.read()


class Device:
    def __init__(self):
        require(os.environ.get('GITHUB_ACTIONS') == 'true')
        require(os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted')
        rows = command(['adb', 'devices']).decode('ascii').strip().splitlines()
        require(len(rows) == 2 and rows[0] == 'List of devices attached')
        serial, state = rows[1].split()
        require(re.fullmatch(r'emulator-[0-9]{4,5}', serial) and state == 'device')
        self.serial = serial
        require(self.shell('getprop ro.kernel.qemu').strip() == b'1')
        require(self.shell('getprop sys.boot_completed').strip() == b'1')
        require(self.shell('am get-current-user').strip() == b'0')
        instruments = self.shell('pm list instrumentation').decode('ascii').splitlines()
        require('instrumentation:' + COMPONENT + ' (target=com.fsm)' in instruments)

    def shell(self, script, seconds=20):
        return command(['adb', '-s', self.serial, 'shell', script], seconds)

    def pid(self):
        # Explicit sentinel distinguishes absent process (pidof exit 1) from adb failure.
        raw = self.shell('p=$(pidof com.fsm); r=$?; if [ "$r" = 0 ]; then printf "PID:%s\\n" "$p"; elif [ "$r" = 1 ]; then echo ABSENT; else exit 1; fi').strip()
        if raw == b'ABSENT':
            return None
        require(re.fullmatch(rb'PID:[1-9][0-9]{0,8}', raw) is not None)
        return int(raw[4:])

    def preflight(self):
        require(self.pid() is None)
        # No app startup, SQLite open, deletes or writes. WorkManager DB is allowed.
        script = '''for d in databases shared_prefs files files/accounts; do
if [ -L "$d" ]; then exit 1; fi
if [ -e "$d" ]; then [ -d "$d" ] && [ -r "$d" ] && [ -x "$d" ] || exit 1; fi
done
for f in databases/fsm_* shared_prefs/tokens_* files/accounts/* files/accounts/.[!.]* files/accounts/..?* files/restart-proof-v1; do
if [ -e "$f" ] || [ -L "$f" ]; then exit 1; fi
done
for f in shared_prefs/account.xml shared_prefs/account.xml.bak; do
if [ -L "$f" ]; then exit 1; fi
if [ -e "$f" ]; then printf 'PREF\\n'; cat "$f" || exit 1; printf '\\nENDPREF\\n'; fi
done'''
        raw = self.shell("run-as com.fsm sh -c '" + script + "'")
        text = raw.decode('utf-8')
        # Only complete empty Android preference maps; never echo content.
        while text:
            require(text.startswith('PREF\n'))
            value, sep, text = text[5:].partition('\nENDPREF\n')
            require(bool(sep) and len(value) <= 4096)
            root = ET.fromstring(value)
            require(root.tag == 'map' and not root.attrib and len(root) == 0 and not (root.text or '').strip())

    def offline(self, configure=True):
        if configure:
            self.shell('cmd connectivity airplane-mode enable')
            self.shell('svc wifi disable')
            self.shell('svc data disable')
        for _ in range(12):
            plane = self.shell('settings get global airplane_mode_on').strip()
            wifi = self.shell('settings get global wifi_on').strip()
            data = self.shell('settings get global mobile_data').strip()
            net = self.shell('dumpsys connectivity').decode('utf-8').splitlines()
            defaults = [s.strip() for s in net if s.strip().startswith('Active default network:')]
            if plane == b'1' and wifi == b'0' and data == b'0' and defaults == ['Active default network: none']:
                return
            time.sleep(1)
        require(False)

    def warm(self):
        self.shell('am start -W --user 0 -n com.fsm/.MainActivity', 40)
        for _ in range(20):
            pid = self.pid()
            if pid is not None:
                return pid
            time.sleep(1)
        require(False)

    def phase(self, phase, run, seed, verify=None):
        method = 'seedDurableState' if phase == 'seed' else 'verifyDurableStateAfterForceStop'
        args = ['am', 'instrument', '--user', '0', '-w', '-r', '--no-restart',
                '-e', 'class', CLASS + '#' + method,
                '-e', 'restartRunId', run, '-e', 'restartSeedPid', str(seed)]
        if verify is not None:
            args += ['-e', 'restartVerifyPid', str(verify)]
        args += [COMPONENT]
        runner_passed(self.shell(' '.join(args), 180), method)

    def marker(self, name):
        require(name in ('manifest', 'seed-result', 'verify-result'))
        return self.shell('run-as com.fsm cat files/restart-proof-v1/' + name + '.json')


def main():
    proof = {'schemaVersion': 1, **dict.fromkeys(FLAGS, False)}
    stage = 'output-preflight'
    try:
        require(not OUT.exists())
        OUT.mkdir(parents=True)
        stage = 'baseline-21'
        baseline_passed()
        proof['baseline21Passed'] = True
        stage = 'hosted-emulator'
        device = Device()
        proof['singleHostedEmulator'] = True
        stage = 'empty-account-preflight'
        device.preflight()
        proof['emptyAccountPreflight'] = True
        stage = 'seed-offline'
        device.offline()
        proof['offlineBeforeSeed'] = True
        run = str(uuid.uuid4())
        seed = device.warm()
        stage = 'seed-runner'
        device.phase('seed', run, seed)
        proof['seedRunnerPassed'] = True
        stage = 'seed-markers'
        manifest = validate_manifest(device.marker('manifest'), run)
        marker = validate_result(device.marker('seed-result'), 'seed')
        for name, obj in [('manifest', manifest), ('seed-result', marker)]:
            (OUT / (name + '.json')).write_text(json.dumps(obj, separators=(',', ':')) + '\n')
        proof['seedProofPassed'] = True
        stage = 'seed-pid-after-finish'
        require(device.pid() == seed)
        proof['seedPidAliveAfterFinish'] = True
        device.offline()
        stage = 'force-stop'
        device.shell('am force-stop --user 0 com.fsm')
        proof['forceStopAfterSeed'] = True
        require(device.pid() is None)
        proof['processAbsentAfterStop'] = True
        stage = 'verify-offline'
        device.offline()
        proof['offlineBeforeVerify'] = True
        verify = device.warm()
        require(verify != seed)
        proof['freshVerifyPid'] = True
        stage = 'verify-runner'
        device.phase('verify', run, seed, verify)
        proof['verifyRunnerPassed'] = True
        stage = 'verify-markers'
        marker = validate_result(device.marker('verify-result'), 'verify')
        proof['verifyProofPassed'] = True
        require(validate_manifest(device.marker('manifest'), run) == manifest)
        proof['manifestUnchanged'] = True
        (OUT / 'verify-result.json').write_text(json.dumps(marker, separators=(',', ':')) + '\n')
        stage = 'verify-pid-after-finish'
        require(device.pid() == verify)
        proof['verifyPidAliveAfterFinish'] = True
        stage = 'verify-offline-after-finish'
        device.offline(configure=False)
        proof['offlineAfterVerify'] = True
        proof['passed'] = True
    except (ValueError, TypeError, KeyError, UnicodeError, OSError, LookupError,
            ET.ParseError, RecursionError, subprocess.SubprocessError):
        # Stage is a code constant, never exception/device/XML/JSON text.
        print('::error::Process restart protocol failed at ' + stage)
        return 1
    finally:
        if OUT.is_dir() and stage != 'output-preflight':
            try:
                (OUT / 'host-proof.json').write_text(json.dumps(proof, separators=(',', ':')) + '\n')
            except OSError:
                print('::error::Process restart host proof could not be written')
                return 1
    print('::notice::Process restart: seed=1 passed; verify=1 passed; PID and durable-state proofs passed')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
