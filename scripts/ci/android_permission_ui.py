#!/usr/bin/env python3
"""Hosted disposable permission UI proof. Never print private runner/device output."""
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time

from check_process_restart import Device, require

CLASS = 'com.fsm.ui.PermissionUiTest'
METHODS = ('cancellingSystemPhotoPickerLeavesDraftPhotoAndOutboxUnchanged',
           'denyingLocationInSystemDialogStillQueuesValidDraftDurably')
COMPONENT = 'com.fsm.test/androidx.test.runner.AndroidJUnitRunner'
CAP = 65536
STAGES = ('hosted', 'device', 'fresh-install', 'install', 'preflight', 'runner', 'publication')
STAGE = 'hosted'
TARGET_DEADLINE = None
PREFLIGHT_KEYS = ('githubHosted', 'singleEmulator', 'userZero', 'freshInstall',
                  'installWithoutGrantFlag', 'emptyAccountStorage', 'runnerComponentVerified')


def stage(name):
    global STAGE
    require(name in STAGES)
    STAGE = name
    print('::notice::Permission UI stage: ' + name, flush=True)


def capture(args, seconds=10):
    """One owned child, private capped streams, monotonic deadline and bounded abort."""
    deadline = time.monotonic() + seconds
    if TARGET_DEADLINE is not None:
        deadline = min(deadline, TARGET_DEADLINE - 20)
    require(deadline > time.monotonic())
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        child = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=out, stderr=err)
        try:
            while True:
                require(os.fstat(out.fileno()).st_size <= CAP and os.fstat(err.fileno()).st_size <= CAP)
                require(time.monotonic() < deadline)
                if child.poll() is not None:
                    break
                time.sleep(.05)
            out.seek(0)
            err.seek(0)
            return child.returncode, out.read(CAP + 1), err.read(CAP + 1)
        finally:
            if child.poll() is None:
                # Only this host adb child. No app-data deletion or remote-join claim.
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)


def command(args, seconds=10):
    code, out, err = capture(args, seconds)
    require(code == 0 and len(out) <= CAP and len(err) <= CAP)
    return out


def parse_runner(raw, error=b'', returncode=0):
    """AndroidX runner 1.6.2 actual packets, not XML or an aggregate OK string.

    InstrumentationResultPrinter: START=1, OK=0, ERROR=-1, FAILURE=-2,
    IGNORED=-3, ASSUMPTION_FAILURE=-4. Final CODE -1 is Activity.RESULT_OK,
    distinct from a testcase STATUS_CODE -1, which is always an error.
    """
    require(type(raw) is bytes and type(error) is bytes and len(raw) <= CAP and len(error) <= CAP)
    require(type(returncode) is int and returncode == 0)
    packets, fields, finals = [], {}, []
    result_started = False
    failure_markers = ('INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED',
                       'INSTRUMENTATION_RESULT: shortMsg=', 'INSTRUMENTATION_RESULT: longMsg=')
    require(not any(marker.encode() in error for marker in failure_markers))
    require(not any(marker in raw or marker in error for marker in (b'Process crashed', b'Process has crashed')))
    for line in raw.decode('utf-8').splitlines():
        require(not any(line.startswith(marker) for marker in failure_markers))
        if line.startswith('INSTRUMENTATION_STATUS: '):
            require(not finals and not result_started)
            key, sep, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            require(bool(sep) and bool(key) and key not in fields)
            fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            require(not finals and not result_started and len(packets) < 4)
            code = line[len('INSTRUMENTATION_STATUS_CODE: '):]
            require(code in ('1', '0'))
            require(all(k in fields for k in ('class', 'test', 'current', 'numtests')))
            require(fields['class'] == CLASS and fields['test'] in METHODS and fields['numtests'] == '2')
            packets.append((code, {k: fields[k] for k in ('class', 'test', 'current', 'numtests')}))
            fields = {}  # Every packet must carry its own identity. No inheritance.
        elif line.startswith('INSTRUMENTATION_RESULT: '):
            # RESULT stream is a tail, never a source of new testcase events. This
            # prevents forged START/PASS lines inside untrusted summary text.
            require(not finals and not fields and len(packets) == 4)
            key, sep, value = line[len('INSTRUMENTATION_RESULT: '):].partition('=')
            require(bool(sep) and bool(key))
            result_started = True
        elif line.startswith('INSTRUMENTATION_CODE: '):
            require(not fields and not finals and len(packets) == 4)
            finals.append(line[len('INSTRUMENTATION_CODE: '):])
        elif line.startswith(('INSTRUMENTATION_STATUS', 'INSTRUMENTATION_CODE', 'INSTRUMENTATION_RESULT')):
            require(False)  # Malformed control records are not opaque stream text.
        # Nonidentity runner fields and opaque stream continuations stay private.
    require(not fields and finals == ['-1'] and [p[0] for p in packets] == ['1', '0', '1', '0'])
    cases = []
    for index in (0, 1):
        started, passed = packets[index * 2][1], packets[index * 2 + 1][1]
        require(started == passed and started['current'] == str(index + 1))
        cases.append({'className': started['class'], 'methodName': started['test'], 'started': 1, 'passed': 1})
    require({c['methodName'] for c in cases} == set(METHODS))
    return sorted(cases, key=lambda c: c['methodName'])


def summary(cases, preflight):
    require(type(preflight) is dict and set(preflight) == set(PREFLIGHT_KEYS))
    require(all(preflight[k] is True for k in PREFLIGHT_KEYS))
    require(type(cases) is list and len(cases) == 2)
    require(cases == [{'className': CLASS, 'methodName': method, 'started': 1, 'passed': 1} for method in METHODS])
    # Bool is not an integer in the public schema, despite Python's equality rule.
    for case in cases:
        require(type(case['started']) is int and type(case['passed']) is int)
    return {'schemaVersion': 1, 'apiLevel': 36,
            'counts': {'started': 2, 'passed': 2, 'failures': 0, 'errors': 0, 'skipped': 0},
            'cases': cases, 'preflight': dict(preflight)}


def publish_summary(obj):
    # Validate the exact public schema again, before touching the artifact directory.
    require(type(obj) is dict and set(obj) == {'schemaVersion', 'apiLevel', 'counts', 'cases', 'preflight'})
    expected = summary(obj['cases'], obj['preflight'])
    require(obj == expected and type(obj['schemaVersion']) is int and type(obj['apiLevel']) is int)
    require(all(type(v) is int for v in obj['counts'].values()))
    repo = Path(os.path.abspath(__file__)).parents[2]
    require(repo == Path.cwd() and repo.resolve() == repo)
    parent = repo
    for part in ('', 'android', 'app', 'build', 'outputs'):
        if part:
            parent = parent / part
        info = parent.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and not info.st_mode & 0o022)
    output = parent / 'permission-ui'
    require(not output.exists() and not output.is_symlink())
    output.mkdir(mode=0o700)  # Never adopt or resume an existing/partial result.
    raw = json.dumps(expected, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')
    require(len(raw) <= 16384)
    path = output / 'summary.json'
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o600)
        require(info.st_nlink == 1 and info.st_size == len(raw) and stream.read(16385) == raw)
    require({p.name for p in output.iterdir()} == {'summary.json'})


class PermissionDevice(Device):
    def shell(self, script, seconds=10):
        return command(['adb', '-s', self.serial, 'shell', script], seconds)


def run():
    global TARGET_DEADLINE
    TARGET_DEADLINE = time.monotonic() + 300
    stage('hosted')
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted')
    require(sys.platform == 'linux' and os.uname().machine == 'x86_64')
    memory = re.search(r'^MemTotal:\s+(\d+) kB$', Path('/proc/meminfo').read_text(), re.M)
    require(memory is not None and int(memory[1]) >= 7 * 1024 * 1024)
    stage('device')
    rows = command(['adb', 'devices']).decode('ascii').strip().splitlines()
    require(len(rows) == 2 and rows[0] == 'List of devices attached')
    serial, state = rows[1].split()
    require(re.fullmatch(r'emulator-[0-9]{4,5}', serial) is not None and state == 'device')
    adb = ['adb', '-s', serial]
    for query, expected in (('getprop ro.kernel.qemu', b'1'), ('getprop sys.boot_completed', b'1'),
                            ('am get-current-user', b'0'), ('getprop ro.build.version.sdk', b'36')):
        require(command(adb + ['shell', query]).strip() == expected)
    stage('fresh-install')
    # Fixed prefix covers both target and test package. Never uninstall/reset to fit.
    require(command(adb + ['shell', 'pm list packages --user 0 com.fsm']).strip() == b'')
    stage('install')
    for apk in ('android/app/build/outputs/apk/debug/app-debug.apk',
                'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
        command(adb + ['install', apk], 30)  # Deliberately no -g, -r or permission mutations.
    stage('preflight')
    require(time.monotonic() < TARGET_DEADLINE - 20)
    device = PermissionDevice()
    require(device.serial == serial)
    device.preflight()  # Read-only process/account/token/Room/files checks, no startup.
    preflight = dict.fromkeys(PREFLIGHT_KEYS, True)
    # Initial fine/coarse denial is ALSO asserted by the actual Android test before
    # any fixture writes; this summary does not claim an independent host API audit.
    stage('runner')
    remaining = TARGET_DEADLINE - time.monotonic() - 20
    require(remaining > 0)
    selectors = ','.join(CLASS + '#' + method for method in METHODS)
    script = 'am instrument --user 0 -w -r -e class ' + selectors + ' ' + COMPONENT
    code, out, err = capture(adb + ['shell', script], min(240, remaining))
    cases = parse_runner(out, err, code)
    stage('publication')
    publish_summary(summary(cases, preflight))
    require(time.monotonic() <= TARGET_DEADLINE)
    print('::notice::Permission UI PASS: started=2 passed=2 failures=0 errors=0 skipped=0; exact two Android assertion bodies and cleanup completed; fresh no-grant install; no raw UI output artifacted')


def main():
    try:
        require(len(sys.argv) == 1)
        run()
        return 0
    except Exception:
        print('::error::Permission UI failed at fixed stage: ' + STAGE, flush=True)
        return 1


if __name__ == '__main__':
    sys.exit(main())
