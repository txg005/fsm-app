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
RUNNER_STAGES = ('runner-capture-stdout-cap', 'runner-capture-stderr-cap',
                 'runner-deadline', 'runner-exit', 'runner-status-grammar',
                 'runner-test-status', 'runner-final')
STAGES += RUNNER_STAGES
METHOD_LABELS = {name: name for name in METHODS}
STATUS_LABELS = {'-1': 'ERROR', '-2': 'FAILURE', '-3': 'IGNORED', '-4': 'ASSUMPTION_FAILURE'}
UNKNOWN_DIAGNOSTIC = 'method=UNKNOWN status=UNKNOWN'
TEST_DIAGNOSTIC = UNKNOWN_DIAGNOSTIC
ASSERTION_TYPES = ('TIMEOUT', 'ASSERTION', 'SECURITY', 'STATE', 'OTHER')
TEST_LINE_LIMIT = 397  # Exact reviewed Android V1 sanitizer bound, not wrapper stack line.
ASSERTION_DIAGNOSTIC = ('UNKNOWN', ())
DENY_DIAGNOSTIC = None
DENY_OBSERVATIONS = frozenset({
    ('NO_FOCUSED_ROOT', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN'),
    ('OTHER_PACKAGE', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN'),
    ('NO_ID_MATCH', 'ZERO', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN'),
    ('NON_ACTIONABLE', 'MANY', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN'),
    ('NON_ACTIONABLE', 'ONE', 'FALSE', 'UNKNOWN', 'UNKNOWN'),
    ('NON_ACTIONABLE', 'ONE', 'TRUE', 'FALSE', 'UNKNOWN'),
    ('NON_ACTIONABLE', 'ONE', 'TRUE', 'TRUE', 'FALSE'),
    ('UNKNOWN', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN'),
})
SAFE_DIAGNOSTICS = {UNKNOWN_DIAGNOSTIC} | {
    'method=' + method + ' status=' + status
    for method in METHOD_LABELS.values() for status in STATUS_LABELS.values()}
STAGE = 'hosted'
TARGET_DEADLINE = None
PREFLIGHT_KEYS = ('githubHosted', 'singleEmulator', 'userZero', 'freshInstall',
                  'installWithoutGrantFlag', 'emptyAccountStorage', 'runnerComponentVerified')


def stage(name):
    global STAGE
    require(name in STAGES)
    STAGE = name
    print('::notice::Permission UI stage: ' + name, flush=True)


def runner_check(ok, failure_stage):
    # Classify only failures, never relax an existing requirement or expose values.
    global STAGE
    if not ok and STAGE in ('runner',) + RUNNER_STAGES:
        require(failure_stage in RUNNER_STAGES)
        STAGE = failure_stage
    require(ok)


def sanitized_assertion(lines):
    # Sole allowed Android V1 transport. Never inspect Throwable messages/causes,
    # arbitrary frame labels or RESULT/stream text for diagnostic evidence.
    if type(lines) is not list or not lines or not all(type(x) is str for x in lines):
        return ('UNKNOWN', ())
    lines = list(lines)
    while lines and lines[-1] == '':
        lines.pop()
    if not lines or len(lines) > 4:
        return ('UNKNOWN', ())
    headers = {'java.lang.AssertionError: PERMISSION_UI_FAILED_V1_TYPE_' + kind: kind
               for kind in ASSERTION_TYPES}
    if lines[0] not in headers:
        return ('UNKNOWN', ())
    numbers = []
    for frame in lines[1:]:
        match = re.fullmatch(r'\tat com\.fsm\.ui\.PermissionUiTest\.checkpoint\(PermissionUiTest\.kt:([1-9][0-9]{0,2})\)', frame)
        if match is None:
            return ('UNKNOWN', ())
        number = int(match[1])
        if number > TEST_LINE_LIMIT or number in numbers:
            return ('UNKNOWN', ())
        numbers.append(number)
    return (headers[lines[0]], tuple(numbers))


def sanitized_failure(lines, method, code):
    # Optional DENY-only TIMEOUT suffix is a closed eight-tuple projection of the
    # producer's last completed existing poll, not a new UI observation by this host.
    unknown = (('UNKNOWN', ()), None)
    if type(lines) is not list or not lines or type(lines[0]) is not str:
        return unknown
    prefix = 'java.lang.AssertionError: PERMISSION_UI_FAILED_V1_TYPE_TIMEOUT'
    headers = {prefix + '_DENYOBS_V1_BRANCH_' + branch + '_MATCH_' + match +
               '_VISIBLE_' + visible + '_ENABLED_' + enabled + '_CLICKABLE_' + clickable:
               (branch, match, visible, enabled, clickable)
               for branch, match, visible, enabled, clickable in DENY_OBSERVATIONS}
    if lines[0] in headers:
        if method != METHODS[1] or code not in ('-1', '-2'):
            return unknown
        assertion = sanitized_assertion([prefix] + lines[1:])
        if assertion[0] != 'TIMEOUT':
            return unknown
        return (assertion, headers[lines[0]])
    # Unknown/invalid suffixes cannot fall back to accepting just their prefix.
    return (sanitized_assertion(lines), None)


def failed_test_diagnostic(code, fields, packets, stack_lines):
    # Failure-only: bound status identity, then exact closed Android V1 metadata.
    # Bind a known failing identity to its immediately preceding validated START.
    global TEST_DIAGNOSTIC, ASSERTION_DIAGNOSTIC, DENY_DIAGNOSTIC
    TEST_DIAGNOSTIC = UNKNOWN_DIAGNOSTIC
    ASSERTION_DIAGNOSTIC = ('UNKNOWN', ())
    DENY_DIAGNOSTIC = None
    identity = {key: fields.get(key) for key in ('class', 'test', 'current', 'numtests')}
    if (code in STATUS_LABELS and len(packets) in (1, 3) and
            packets[-1][0] == '1' and packets[-1][1] == identity and
            identity['class'] == CLASS and identity['test'] in METHOD_LABELS and
            identity['numtests'] == '2' and identity['current'] == str((len(packets) + 1) // 2)):
        TEST_DIAGNOSTIC = ('method=' + METHOD_LABELS[identity['test']] +
                           ' status=' + STATUS_LABELS[code])
        ASSERTION_DIAGNOSTIC, DENY_DIAGNOSTIC = sanitized_failure(stack_lines, identity['test'], code)


def capture(args, seconds=10):
    """One owned child, private capped streams, monotonic deadline and bounded abort."""
    deadline = time.monotonic() + seconds
    if TARGET_DEADLINE is not None:
        deadline = min(deadline, TARGET_DEADLINE - 20)
    runner_check(deadline > time.monotonic(), 'runner-deadline')
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        child = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=out, stderr=err)
        try:
            while True:
                runner_check(os.fstat(out.fileno()).st_size <= CAP, 'runner-capture-stdout-cap')
                runner_check(os.fstat(err.fileno()).st_size <= CAP, 'runner-capture-stderr-cap')
                runner_check(time.monotonic() < deadline, 'runner-deadline')
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
    global STAGE, TEST_DIAGNOSTIC, ASSERTION_DIAGNOSTIC, DENY_DIAGNOSTIC
    STAGE = 'runner-status-grammar'
    TEST_DIAGNOSTIC = UNKNOWN_DIAGNOSTIC
    ASSERTION_DIAGNOSTIC = ('UNKNOWN', ())
    DENY_DIAGNOSTIC = None
    require(type(raw) is bytes and type(error) is bytes)
    runner_check(len(raw) <= CAP, 'runner-capture-stdout-cap')
    runner_check(len(error) <= CAP, 'runner-capture-stderr-cap')
    runner_check(type(returncode) is int and returncode == 0, 'runner-exit')
    packets, fields, finals = [], {}, []
    stack_lines, stack_active = [], False
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
            stack_active = key == 'stack'
            if stack_active:
                stack_lines = [value]
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            require(not finals and not result_started and len(packets) < 4)
            code = line[len('INSTRUMENTATION_STATUS_CODE: '):]
            if code not in ('1', '0'):
                failed_test_diagnostic(code, fields, packets, stack_lines)
                runner_check(False, 'runner-test-status')
            require(all(k in fields for k in ('class', 'test', 'current', 'numtests')))
            require(fields['class'] == CLASS and fields['test'] in METHODS and fields['numtests'] == '2')
            packets.append((code, {k: fields[k] for k in ('class', 'test', 'current', 'numtests')}))
            fields = {}  # Every packet must carry its own identity. No inheritance.
            stack_lines, stack_active = [], False
        elif line.startswith('INSTRUMENTATION_RESULT: '):
            # RESULT stream is a tail, never a source of new testcase events. This
            # prevents forged START/PASS lines inside untrusted summary text.
            require(not finals and not fields and len(packets) == 4)
            key, sep, value = line[len('INSTRUMENTATION_RESULT: '):].partition('=')
            require(bool(sep) and bool(key))
            result_started = True
        elif line.startswith('INSTRUMENTATION_CODE: '):
            runner_check(not fields and not finals and len(packets) == 4, 'runner-final')
            finals.append(line[len('INSTRUMENTATION_CODE: '):])
        elif line.startswith(('INSTRUMENTATION_STATUS', 'INSTRUMENTATION_CODE', 'INSTRUMENTATION_RESULT')):
            require(False)  # Malformed control records are not opaque stream text.
        elif stack_active:
            stack_lines.append(line)
        # Nonidentity runner fields and opaque stream continuations stay private.
    runner_check(not fields and finals == ['-1'], 'runner-final')
    require([p[0] for p in packets] == ['1', '0', '1', '0'])
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
        if STAGE == 'runner-test-status':
            safe = TEST_DIAGNOSTIC if TEST_DIAGNOSTIC in SAFE_DIAGNOSTICS else UNKNOWN_DIAGNOSTIC
            print('::error::Permission UI test diagnostic: ' + safe, flush=True)
            kind, numbers = ASSERTION_DIAGNOSTIC
            valid = (safe != UNKNOWN_DIAGNOSTIC and kind in ASSERTION_TYPES and
                     type(numbers) is tuple and len(numbers) <= 3 and
                     all(type(n) is int and 1 <= n <= TEST_LINE_LIMIT for n in numbers) and
                     len(set(numbers)) == len(numbers))
            label = kind if valid else 'UNKNOWN'
            line_labels = ','.join(str(n) for n in numbers) if valid and numbers else 'UNKNOWN'
            print('::error::Permission UI sanitized assertion: type=' + label +
                  ' testLines=' + line_labels, flush=True)
            if (valid and kind == 'TIMEOUT' and
                    safe in {'method=' + METHODS[1] + ' status=' + STATUS_LABELS[code]
                             for code in ('-1', '-2')} and
                    type(DENY_DIAGNOSTIC) is tuple and DENY_DIAGNOSTIC in DENY_OBSERVATIONS):
                branch, match, visible, enabled, clickable = DENY_DIAGNOSTIC
                print('::error::Permission UI DENY observation: branch=' + branch +
                      ' match=' + match + ' visible=' + visible +
                      ' enabled=' + enabled + ' clickable=' + clickable, flush=True)
        return 1


if __name__ == '__main__':
    sys.exit(main())
