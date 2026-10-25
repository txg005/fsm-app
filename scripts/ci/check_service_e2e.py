#!/usr/bin/env python3
"""Disposable hosted Service E2E only. Raw subprocess/control data is NEVER printed."""
import datetime as dt
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import time

from check_process_restart import Device, require

CLASS = 'com.fsm.servicee2e.ServiceE2ETest'
METHOD = 'visibleSendRunsForegroundServiceToRealBackendAndUpdatesUi'
COMPONENT = 'com.fsm.test/androidx.test.runner.AndroidJUnitRunner'
APPROOT = 'files/service-e2e-v1/'
SERVICE = 'com.fsm/.service.SyncService'
UUID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}')
SHA = re.compile(r'[0-9a-f]{64}')
STAMP = re.compile(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z')
JPEG = '4a0043beb47e76e4bf6a12e9d93832dc69d596c8d1b114870e17f9d1420d61bf'
OUT = Path('android/app/build/outputs/service-e2e')
PG = Path('/usr/lib/postgresql/16/bin')
STAGES = ('prepare', 'hostenv', 'compile', 'postgres', 'device', 'preflight', 'install',
          'host-ready', 'reverse', 'clock', 'fixture', 'runner', 'initial', 'close',
          'result', 'host-proof', 'cleanup')
STAGE = 'prepare'
RESULT_FLAGS = ('workerQuiescent workerReservationVerified serviceObservedForeground '
                'serviceNotificationObserved serviceSameInstanceAtClose workerQuiescentAtClose '
                'closeBarrierObserved uploadACK closeACK liveUiClosed').split()
RESULT_IDS = 'uploadOperationId closeOperationId resultId localPhotoId serverPhotoId'.split()
SCHEMAS = {
    'ready': 'schemaVersion fixtureExpectationVersion runNonce hostPort hostReadyAt hostDeadlineAt',
    'fixture': 'schemaVersion fixtureExpectationVersion runNonce employeeId workOrderId checklistItemIds expectedJpegSha256 expectedJpegByteSize login password',
    'service-ready': 'schemaVersion runNonce servicePid serviceActiveSince workerQuiescent workerReservationVerified serviceForeground notificationObserved',
    'release-service': 'schemaVersion runNonce servicePid serviceActiveSince dataSyncObserved workerQuiescent workerReservationVerified',
    'close-committed': 'schemaVersion runNonce gateNonce closeOperationId hostCommittedAt releaseDeadlineAt',
    'close-observed': 'schemaVersion runNonce gateNonce closeOperationId servicePid serviceActiveSince workerQuiescent workerReservationVerified serviceForeground notificationObserved uploadACK closeRUNNING liveUiQueued',
    'close-release': 'schemaVersion runNonce gateNonce closeOperationId servicePid serviceActiveSince serviceForeground dataSyncObserved notificationObserved workerQuiescent workerReservationVerified',
    'outcome': 'schemaVersion runNonce androidExitCode',
    'abort': 'schemaVersion runNonce code',
    'hostproof': 'schemaVersion runNonce status sqlVerified receiptsVerified privateJpegVerified foreignPhoto404',
    'result': 'schemaVersion fixtureExpectationVersion runNonce ' + ' '.join(RESULT_IDS) + ' jpegSha256 jpegByteSize resultCapturedAt closeFrozenPayloadSha256 closeWireBodySha256 ' + ' '.join(RESULT_FLAGS) + ' finalLifecycle',
}


def stage(name):
    global STAGE
    require(name in STAGES)
    STAGE = name
    print('::notice::Service E2E stage: ' + name, flush=True)


def number(value, low=1, high=2**63-1):
    require(type(value) is int and low <= value <= high)


def identifier(value):
    require(type(value) is str and UUID.fullmatch(value) is not None)


def instant(value):
    require(type(value) is str and STAMP.fullmatch(value) is not None)
    return dt.datetime.strptime(value, '%Y-%m-%dT%H:%M:%S.%fZ').replace(tzinfo=dt.timezone.utc).timestamp()


def encode(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')


def decode(raw):
    require(0 < len(raw) <= 16384)
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result)
            result[key] = value
        return result
    def invalid(_):
        raise ValueError('Invalid protocol')
    value = json.loads(raw.decode('utf-8'), object_pairs_hook=pairs, parse_constant=invalid)
    require(type(value) is dict and encode(value) == raw)
    return value


def validate(kind, obj, nonce=None):
    require(type(obj) is dict and set(obj) == set(SCHEMAS[kind].split()))
    number(obj['schemaVersion'], 1, 1)
    identifier(obj['runNonce'])
    require(nonce is None or obj['runNonce'] == nonce)
    if 'fixtureExpectationVersion' in obj:
        number(obj['fixtureExpectationVersion'], 1, 1)
    for key in ('servicePid', 'serviceActiveSince'):
        if key in obj:
            number(obj[key], 1, 2**31-1 if key == 'servicePid' else 2**63-1)
    for key in ('gateNonce', 'closeOperationId'):
        if key in obj:
            identifier(obj[key])
    for key in ('workerQuiescent', 'workerReservationVerified', 'serviceForeground',
                'notificationObserved', 'dataSyncObserved', 'uploadACK', 'closeRUNNING', 'liveUiQueued'):
        if key in obj:
            require(obj[key] is True)
    if kind == 'ready':
        number(obj['hostPort'], 1024, 65535)
        require(0 < instant(obj['hostDeadlineAt']) - instant(obj['hostReadyAt']) <= 480)
    elif kind == 'fixture':
        for key in ('employeeId', 'workOrderId'):
            identifier(obj[key])
        require(type(obj['checklistItemIds']) is list and len(obj['checklistItemIds']) == 1)
        identifier(obj['checklistItemIds'][0])
        require(obj['expectedJpegSha256'] == JPEG)
        number(obj['expectedJpegByteSize'], 631, 631)
        for key in ('login', 'password'):
            require(type(obj[key]) is str and 1 <= len(obj[key]) <= 256)
            require(not any(ord(c) < 32 or ord(c) == 127 for c in obj[key]))
    elif kind == 'close-committed':
        require(0 < instant(obj['releaseDeadlineAt']) - instant(obj['hostCommittedAt']) <= 15)
    elif kind == 'result':
        for key in RESULT_IDS:
            identifier(obj[key])
        require(len({obj[k] for k in RESULT_IDS}) == len(RESULT_IDS))
        require(all(obj[key] is True for key in RESULT_FLAGS))
        for key in ('jpegSha256', 'closeFrozenPayloadSha256', 'closeWireBodySha256'):
            require(type(obj[key]) is str and SHA.fullmatch(obj[key]) is not None)
        require(obj['jpegSha256'] == JPEG and obj['finalLifecycle'] == 'CLOSED')
        number(obj['jpegByteSize'], 631, 631)
        instant(obj['resultCapturedAt'])
    elif kind == 'hostproof':
        require(obj['status'] == 'PASS')
        require(all(obj[k] is True for k in ('sqlVerified', 'receiptsVerified', 'privateJpegVerified', 'foreignPhoto404')))
    elif kind == 'outcome':
        number(obj['androidExitCode'], 0, 0)
    elif kind == 'abort':
        require(obj['code'] == 'ORCHESTRATION_FAILED')
    return obj


def read_private(path, limit=16384):
    # O_NOFOLLOW + descriptor checks; no symlink adoption or unbounded file reads.
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid())
        require(stat.S_IMODE(info.st_mode) == 0o600 and info.st_nlink == 1)
        require(0 < info.st_size <= limit)
        raw = stream.read(limit + 1)
        require(len(raw) <= limit)
        return raw


def publish(path, obj):
    raw = encode(obj)
    require(len(raw) <= 16384 and path.parent.resolve() == path.parent)
    # Link complete temporary inode atomically, without replacing existing destination.
    fd, name = tempfile.mkstemp(prefix='.new-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(name, path, follow_symlinks=False)
    finally:
        os.unlink(name)


class Capture:
    """One bounded child. Temporary streams are private, never logged/artifacted."""
    def __init__(self, args, seconds, cap=1024*1024, input_data=None, env=None, cwd=None):
        self.out, self.err = tempfile.TemporaryFile(), tempfile.TemporaryFile()
        self.cap = cap
        self.deadline = time.monotonic() + seconds
        self.input = None
        if input_data is not None:
            require(len(input_data) <= 16384)
            self.input = tempfile.TemporaryFile()
            self.input.write(input_data)
            self.input.seek(0)
        # A private seekable stdin avoids blocking on a full PIPE before timeout polling.
        self.proc = subprocess.Popen(args, stdin=self.input if self.input is not None else subprocess.DEVNULL,
                                     stdout=self.out, stderr=self.err, env=env, cwd=cwd)

    def check(self):
        require(os.fstat(self.out.fileno()).st_size <= self.cap and os.fstat(self.err.fileno()).st_size <= self.cap)
        require(time.monotonic() < self.deadline)

    def finish(self):
        while self.proc.poll() is None:
            self.check()
            time.sleep(.05)
        self.check()
        require(self.proc.returncode == 0)
        self.out.seek(0)
        return self.out.read(self.cap + 1)

    def close(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=5)
        self.out.close()
        self.err.close()
        if self.input is not None:
            self.input.close()


def command(args, seconds=10, cap=1024*1024, **kwargs):
    child = Capture(args, seconds, cap, **kwargs)
    try:
        return child.finish()
    finally:
        child.close()


def hosted():
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted')
    require(sys.platform == 'linux' and os.uname().machine == 'x86_64')
    memory = re.search(r'^MemTotal:\s+(\d+) kB$', Path('/proc/meminfo').read_text(), re.M)
    require(memory is not None and int(memory[1]) >= 7 * 1024 * 1024)


def root_path():
    root = Path(os.environ['FSM_SERVICE_E2E_PRIVATE'])
    require(root.is_absolute() and root.resolve() == root and root.name.startswith('fsm-service-e2e-'))
    require(root.parent == Path(os.environ['RUNNER_TEMP']).resolve())
    info = root.stat()
    require(info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700)
    return root


def runner_passed(raw):
    require(len(raw) <= 65536)
    statuses, fields, finals = [], {}, []
    for line in raw.decode('utf-8').splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, sep, value = line[24:].partition('=')
            require(bool(sep) and key not in fields)
            fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            statuses.append((line.split(': ', 1)[1], fields))
            fields = {}
        elif line.startswith('INSTRUMENTATION_CODE: '):
            finals.append(line.split(': ', 1)[1])
        elif line.startswith(('INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED', 'INSTRUMENTATION_RESULT: shortMsg=')):
            require(False)
    require(not fields and finals == ['-1'] and len(statuses) == 2)
    require([s[0] for s in statuses] == ['1', '0'])
    for _, fields in statuses:
        require(fields.get('class') == CLASS and fields.get('test') == METHOD)
        require(fields.get('current') == '1' and fields.get('numtests') == '1')


def runtime_service(raw, pid):
    """AOSP16 ServiceRecord.dump + Notification.toString; unknown format fails closed.

    Identity token/PID are private. Numeric activeSince is NOT reconstructed here.
    """
    require(len(raw) <= 262144)
    text = raw.decode('utf-8')
    starts = list(re.finditer(r'^\s*\* ServiceRecord\{([0-9a-f]+) u0 com\.fsm/\.service\.SyncService(?: c:[A-Za-z0-9._]+)?\}\s*$', text, re.M))
    require(len(starts) == 1)
    # Other active ServiceRecord blocks are not attributable to this positive fixture.
    require(len(re.findall(r'^\s*\* ServiceRecord\{', text, re.M)) == 1)
    block = text[starts[0].end():]
    apps = re.findall(r'^\s*app=ProcessRecord\{[0-9a-f]+ ([1-9][0-9]*):com\.fsm/u0a[0-9]+\}\s*$', block, re.M)
    require(apps == [str(pid)])
    fg = re.findall(r'^\s*isForeground=true foregroundId=41 types=0x00000001 foregroundNoti=(Notification\([^\r\n]+\))\s*$', block, re.M)
    require(len(fg) == 1 and fg[0].startswith('Notification(channel=sync '))
    flags = re.findall(r' flags=([A-Z_|]+)(?=\s|\))', fg[0])
    require(len(flags) == 1)
    tokens = flags[0].split('|')
    known = set(('SHOW_LIGHTS ONGOING_EVENT INSISTENT ONLY_ALERT_ONCE AUTO_CANCEL NO_CLEAR '
                 'FOREGROUND_SERVICE HIGH_PRIORITY LOCAL_ONLY GROUP_SUMMARY AUTOGROUP_SUMMARY '
                 'CAN_COLORIZE BUBBLE NO_DISMISS FSI_REQUESTED_BUT_DENIED USER_INITIATED_JOB '
                 'LIFETIME_EXTENDED_BY_DIRECT_REPLY PROMOTED_ONGOING SILENT').split())
    require(len(set(tokens)) == len(tokens) and set(tokens) <= known and 'FOREGROUND_SERVICE' in tokens)
    return starts[0][1], pid


class ServiceDevice(Device):
    deadline = None

    def shell(self, script, seconds=5, input_data=None, cap=262144):
        if self.deadline is not None:
            seconds = min(seconds, self.deadline - time.monotonic())
            require(seconds > 0)
        return command(['adb', '-s', self.serial, 'shell', script], seconds, cap, input_data=input_data)

    def preflight(self):
        super().preflight()
        self.shell('run-as com.fsm sh -c ' + shlex.quote('[ ! -e files/service-e2e-v1 ] && [ ! -L files/service-e2e-v1 ]'))
        raw = self.shell('dumpsys activity -a services com.fsm')
        require(b'ServiceRecord{' not in raw)

    def app_read(self, name):
        require(name in ('service-ready', 'close-observed', 'result'))
        # Absent sentinel is not a parse failure; stderr/raw output never escapes.
        path = APPROOT + name + '.json'
        script = ('[ -d ' + APPROOT + ' ] && [ ! -L ' + APPROOT + ' ] && '
                  'if [ ! -e ' + path + ' ] && [ ! -L ' + path + ' ]; then printf ABSENT; '
                  'else [ -f ' + path + ' ] && [ ! -L ' + path + ' ] && '
                  '[ "$(stat -c %a ' + path + ')" = 600 ] && cat ' + path + '; fi')
        raw = self.shell('run-as com.fsm sh -c ' + shlex.quote(script), cap=16384)
        return None if raw == b'ABSENT' else decode(raw)

    def app_write(self, name, obj):
        require(name in ('fixture', 'release-service', 'close-committed'))
        prefix = ('mkdir ' + APPROOT + ' && ') if name == 'fixture' else ''
        # Two shells: quote the whole remote sh -c program, stdin carries JSON only.
        script = ('umask 077; set -C; ' + prefix + '[ -d ' + APPROOT + ' ] && [ ! -L ' + APPROOT + ' ] && '
                  'cat > ' + APPROOT + '.' + name + '.incoming && '
                  'ln ' + APPROOT + '.' + name + '.incoming ' + APPROOT + name + '.json && '
                  'rm ' + APPROOT + '.' + name + '.incoming')
        self.shell('run-as com.fsm sh -c ' + shlex.quote(script), input_data=encode(obj))

    def runtime(self, pid):
        return runtime_service(self.shell('dumpsys activity -a services com.fsm'), pid)


def wait_file(path, seconds, host=None):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if path.exists() or path.is_symlink():
            return decode(read_private(path))
        if host is not None:
            host.check()
            require(host.proc.poll() is None)
        time.sleep(.1)
    require(False)


def wait_app(device, name, seconds, runner, host):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        runner.check()
        host.check()
        require(host.proc.poll() is None)
        value = device.app_read(name)
        if value is not None:
            return value
        require(runner.proc.poll() is None)
        time.sleep(.1)
    require(False)


def prepare():
    stage('hostenv')
    hosted()
    require('FSM_SERVICE_E2E_PRIVATE' not in os.environ)
    root = Path(tempfile.mkdtemp(prefix='fsm-service-e2e-', dir=os.environ['RUNNER_TEMP']))
    os.chmod(root, 0o700)
    # Persist only the private root path for later workflow steps, never credential material.
    with open(os.environ['GITHUB_ENV'], 'a') as envfile:
        envfile.write('FSM_SERVICE_E2E_PRIVATE=' + str(root) + '\n')
    stage('postgres')
    for binary in ('initdb', 'pg_ctl', 'psql'):
        require(re.search(rb'\b16\.[0-9]+\b', command([str(PG / binary), '--version'])) is not None)
    password = secrets.token_hex(32)
    pwfile = root / 'pg-password'
    fd = os.open(pwfile, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(password)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    (root / 'socket').mkdir(mode=0o700)
    # State written BEFORE cluster startup, so finally can stop only this exact owned PGDATA.
    publish(root / 'state.json', {'port': port, 'password': password})
    command([str(PG / 'initdb'), '-D', str(root / 'pgdata'), '-U', 'fsm_service_e2e',
             '--pwfile=' + str(pwfile), '--auth-local=trust', '--auth-host=scram-sha-256', '--no-locale', '-E', 'UTF8'], 30)
    command([str(PG / 'pg_ctl'), '-D', str(root / 'pgdata'), '-l', str(root / 'pg.log'), '-w', '-t', '25',
             '-o', f'-h 127.0.0.1 -p {port} -k {root}/socket -c max_connections=12 -c shared_buffers=32MB', 'start'], 30)
    args = [str(PG / 'psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-h', str(root / 'socket'), '-p', str(port),
            '-U', 'fsm_service_e2e', '-d', 'postgres', '-Atc']
    version = command(args + ['SHOW server_version_num']).strip()
    require(re.fullmatch(rb'[0-9]{6}', version) is not None and 160000 <= int(version) <= 169999)
    print('::notice::Service E2E native PostgreSQL server_version_num=' + version.decode('ascii'))
    command(args + ['CREATE DATABASE fsm_service_e2e'])
    stage('compile')
    flags = ['--no-daemon', '--no-parallel', '--max-workers=1', '-Pkotlin.compiler.execution.strategy=in-process', '--console=plain']
    command(['sh', 'gradlew', *flags, '-I', '../scripts/ci/service_e2e_classpath.init.gradle.kts',
             '-PserviceE2eClasspathOutput=' + str(root / 'classpath'), 'exportServiceE2eClasspath'],
            540, 4*1024*1024, cwd='backend')
    command(['sh', 'gradlew', *flags, '-PfsmApiUrl=http://127.0.0.1:18080', 'assembleDebug', 'assembleDebugAndroidTest'],
            540, 4*1024*1024, cwd='android')


def process_start(pid):
    raw = Path('/proc', str(pid), 'stat').read_text()
    return raw[raw.rfind(')') + 2:].split()[19]


def stop_recorded_host(root):
    marker = root / 'host-process.json'
    if not marker.exists():
        return
    record = decode(read_private(marker))
    require(set(record) == {'pid', 'start'})
    number(record['pid'])
    require(type(record['start']) is str and re.fullmatch(r'[0-9]+', record['start']) is not None)
    pid = record['pid']
    if Path('/proc', str(pid)).exists():
        require(process_start(pid) == record['start'])
        args = Path('/proc', str(pid), 'cmdline').read_bytes().split(b'\x00')
        require(b'fsm.AndroidServiceE2EHost' in args)
        require(('-Dfsm.serviceE2e.controlRoot=' + str(root / 'control')).encode() in args)
        os.kill(pid, 15)
        deadline = time.monotonic() + 10
        while Path('/proc', str(pid)).exists() and time.monotonic() < deadline:
            time.sleep(.1)
        require(not Path('/proc', str(pid)).exists())
    marker.unlink()


def run():
    stage('hostenv')
    hosted()
    root = root_path()
    host = runner = device = None
    reverse = False
    nonce = None
    completed = False
    try:
        stage('device')
        # APK installation is the only setup install; never reinstall/clear/reset between phases.
        # Device guards include runner component, so install BEFORE their component check.
        rows = command(['adb', 'devices']).decode('ascii').strip().splitlines()
        require(len(rows) == 2 and rows[0] == 'List of devices attached')
        serial, state = rows[1].split()
        require(re.fullmatch(r'emulator-[0-9]{4,5}', serial) is not None and state == 'device')
        require(command(['adb', '-s', serial, 'shell', 'getprop ro.kernel.qemu']).strip() == b'1')
        require(command(['adb', '-s', serial, 'shell', 'getprop sys.boot_completed']).strip() == b'1')
        require(command(['adb', '-s', serial, 'shell', 'am get-current-user']).strip() == b'0')
        stage('install')
        command(['adb', '-s', serial, 'install', 'android/app/build/outputs/apk/debug/app-debug.apk'], 30)
        command(['adb', '-s', serial, 'install', 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'], 30)
        device = ServiceDevice()
        require(device.serial == serial)
        stage('preflight')
        device.preflight()
        device.shell('pm grant com.fsm android.permission.POST_NOTIFICATIONS')
        stage('host-ready')
        state = decode(read_private(root / 'state.json'))
        require(set(state) == {'port', 'password'})
        number(state['port'], 1024, 65535)
        require(type(state['password']) is str and re.fullmatch(r'[0-9a-f]{64}', state['password']) is not None)
        cp = read_private(root / 'classpath', 1024*1024).decode('utf-8')
        require(cp and not any(ord(c) < 32 for c in cp))
        env = dict(os.environ, TEST_DATABASE_URL=f"jdbc:postgresql://127.0.0.1:{state['port']}/fsm_service_e2e",
                   TEST_DATABASE_USER='fsm_service_e2e', TEST_DATABASE_PASSWORD=state['password'], FSM_SERVICE_E2E_DISPOSABLE='1')
        host = Capture(['java', '-Xmx384m', '-XX:MaxMetaspaceSize=192m', '-XX:ActiveProcessorCount=2',
                        '-Dfsm.serviceE2e.controlRoot=' + str(root / 'control'), '-cp', cp, 'fsm.AndroidServiceE2EHost'], 550, env=env)
        publish(root / 'host-process.json', {'pid': host.proc.pid, 'start': process_start(host.proc.pid)})
        control = root / 'control'
        ready = validate('ready', wait_file(control / 'ready.json', 60, host))
        nonce = ready['runNonce']
        require(abs(time.time() - instant(ready['hostReadyAt'])) <= 5)
        fixture = validate('fixture', wait_file(control / 'fixture.json', 5, host), nonce)
        stage('reverse')
        mappings = command(['adb', '-s', serial, 'reverse', '--list']).decode('ascii').splitlines()
        for line in mappings:
            fields = line.split()
            require(len(fields) == 3 and fields[1] != 'tcp:18080')
        command(['adb', '-s', serial, 'reverse', 'tcp:18080', 'tcp:' + str(ready['hostPort'])])
        reverse = True
        actual = command(['adb', '-s', serial, 'reverse', '--list']).decode('ascii').splitlines()
        require(sum(line.split()[1:] == ['tcp:18080', 'tcp:' + str(ready['hostPort'])] for line in actual) == 1)
        stage('clock')
        before, mono = time.time(), time.monotonic()
        epoch = device.shell('date +%s').strip()
        after = time.time()
        require(time.monotonic() - mono <= 2 and 0 <= after - before <= 2)
        require(re.fullmatch(rb'[0-9]{10}', epoch) is not None)
        # Seconds command describes [epoch,epoch+1); bound both ends, not invented millis.
        require(max(abs(int(epoch) - after), abs(int(epoch) + 1 - before)) <= 5)
        stage('fixture')
        device.app_write('fixture', fixture)
        stage('runner')
        script = 'am instrument -w -r -e class ' + CLASS + '#' + METHOD + ' -e runNonce ' + nonce + ' ' + COMPONENT
        runner = Capture(['adb', '-s', serial, 'shell', script], 150, 65536)
        stage('initial')
        initial = validate('service-ready', wait_app(device, 'service-ready', 30, runner, host), nonce)
        identity = device.runtime(initial['servicePid'])
        device.app_write('release-service', validate('release-service', {
            'schemaVersion': 1, 'runNonce': nonce, 'servicePid': initial['servicePid'],
            'serviceActiveSince': initial['serviceActiveSince'], 'dataSyncObserved': True,
            'workerQuiescent': True, 'workerReservationVerified': True}, nonce))
        stage('close')
        commit = validate('close-committed', wait_file(control / 'close-committed.json', 60, host), nonce)
        remaining = instant(commit['releaseDeadlineAt']) - time.time()
        require(0 < remaining <= 20)  # wall skew allowance cannot extend local monotonic 15s.
        close_deadline = time.monotonic() + min(15, remaining)
        device.deadline = close_deadline
        device.app_write('close-committed', commit)
        observed = validate('close-observed', wait_app(device, 'close-observed', max(.1, close_deadline-time.monotonic()-3), runner, host), nonce)
        for key in ('gateNonce', 'closeOperationId'):
            require(observed[key] == commit[key])
        for key in ('servicePid', 'serviceActiveSince'):
            require(observed[key] == initial[key])
        require(device.runtime(initial['servicePid']) == identity and time.monotonic() < close_deadline)
        release = {k: observed[k] for k in ('schemaVersion', 'runNonce', 'gateNonce', 'closeOperationId',
                                           'servicePid', 'serviceActiveSince', 'serviceForeground',
                                           'notificationObserved', 'workerQuiescent', 'workerReservationVerified')}
        release['dataSyncObserved'] = True
        publish(control / 'close-release.json', validate('close-release', release, nonce))
        device.deadline = None
        stage('result')
        runner_passed(runner.finish())
        result = validate('result', device.app_read('result'), nonce)
        require(result['closeOperationId'] == commit['closeOperationId'])
        require(instant(ready['hostReadyAt']) - 5 <= instant(result['resultCapturedAt']) <= time.time() + 5)
        publish(control / 'android-result.json', result)
        publish(control / 'outcome.json', validate('outcome', {'schemaVersion': 1, 'runNonce': nonce, 'androidExitCode': 0}, nonce))
        stage('host-proof')
        host.deadline = min(host.deadline, time.monotonic() + 40)
        require(host.finish().strip() == b'E2E_HOST_PASS')
        proof = validate('hostproof', decode(read_private(control / 'hostproof.json')), nonce)
        require(validate('ready', decode(read_private(control / 'ready.json')), nonce) == ready)
        require(validate('result', decode(read_private(control / 'result.json')), nonce) == result)
        require({p.name for p in control.iterdir()} == {'ready.json', 'result.json', 'hostproof.json'})
        require(not OUT.exists() and not OUT.is_symlink())
        OUT.mkdir(parents=True, mode=0o700)
        for name, obj in [('ready', ready), ('result', result), ('hostproof', proof)]:
            publish(OUT / (name + '.json'), obj)
        completed = True
    finally:
        # Do not abort then claim host waits for Android: backend abort tears server down.
        # Wait bounded for sanitizer/cleanup first. Close-gate timeout may already kill host.
        if runner is not None and runner.proc.poll() is None:
            until = min(runner.deadline, time.monotonic() + 30)
            while runner.proc.poll() is None and time.monotonic() < until:
                time.sleep(.1)
        if not completed and nonce is not None and (root / 'control').is_dir():
            try:
                publish(root / 'control' / 'abort.json', validate('abort', {
                    'schemaVersion': 1, 'runNonce': nonce, 'code': 'ORCHESTRATION_FAILED'}, nonce))
            except (OSError, ValueError):
                pass  # still fail; never turn a missing abort sink into success.
        if runner is not None:
            runner.close()
        if host is not None:
            host.close()
            (root / 'host-process.json').unlink(missing_ok=True)
        if reverse:
            command(['adb', '-s', device.serial, 'reverse', '--remove', 'tcp:18080'])
        # No host-side deletion of any application DB/photos/account files.
    require(completed)
    print('::notice::Service E2E PASS: target started=1 passed=1 failures=0 errors=0 skips=0; independent initial/close FGS dataSync notification stableRecord=true; Android activeSince assertion=true; backend SQL JPEG proof=true; app DB/photos retained for disposable emulator teardown (not full storage cleanup or coroutine join proof)')


def cleanup():
    stage('cleanup')
    hosted()
    root = root_path()
    stop_recorded_host(root)
    if (root / 'pgdata' / 'postmaster.pid').exists():
        command([str(PG / 'pg_ctl'), '-D', str(root / 'pgdata'), '-w', '-t', '25', '-m', 'fast', 'stop'], 30)
    shutil.rmtree(root)  # only mkdtemp-created host tree; never app data/global PG.


def main():
    try:
        require(len(sys.argv) == 2 and sys.argv[1] in ('prepare', 'run', 'cleanup'))
        {'prepare': prepare, 'run': run, 'cleanup': cleanup}[sys.argv[1]]()
        return 0
    except Exception:
        # No exception text, argv, output, PID, paths, fixtures or raw manifests.
        print('::error::Service E2E failed at fixed stage: ' + STAGE, flush=True)
        return 1


if __name__ == '__main__':
    sys.exit(main())
