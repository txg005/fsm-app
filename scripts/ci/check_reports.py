#!/usr/bin/env python3
"""Fail closed on absent, failing, skipped, or incomplete JUnit XML suites."""
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

BASELINE = {
    'localCrudAndPendingLock',
    'transactionRollsBackFinalAndOutbox',
    'restartKeepsUuidFrozenBytesAndRecoversRunning',
    'uploadBeforeCloseDependenciesAndLocalPathRetention',
    'loggerBoundedAndWhitelisted',
    'orphanCleanupNeverDeletesRegisteredQueuedPhoto',
    'importCorrectsExifBeforeStripping',
    'conflictTransitionBeforeCancelledFetchSurvivesRestart',
    'failedAuthorizedResolveNeverUsesStaleShadowOrDiscardsQueue',
    'changedClosedCanonicalRequiresNewChoiceAndCannotReopenDraft',
    'atomicConflictRollsBackAndLegacyInterruptedStateRepairs',
    'unknownReceiptRetainsFrozenWireAndBackoffThenSameOperationAcks',
    'cancelAfterDispatchKeepsRetryableSameFrozenOperation',
    'missingPhotoIsDefinitiveBeforeDispatchAndMakesNoMutationRequest',
}
SUITES = {
    'backend': [('backend/build/test-results/test/TEST-*.xml', 9),
                ('backend/build/test-results/integrationTest/TEST-*.xml', 11)],
    'android': [('android/app/build/test-results/testDebugUnitTest/TEST-*.xml', 13)],
    'instrumentation': [('android/app/build/outputs/androidTest-results/connected/**/*.xml', 14)],
}


# Untrusted report fields are accepted whole or replaced, never partially cleaned.
IDENTIFIER = re.compile(r'[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*', re.ASCII)
FRAME = re.compile(
    r'\s*at (com\.fsm\.(?:[A-Za-z_][A-Za-z0-9_]*\.)*[A-Za-z_][A-Za-z0-9_]*(?:\$[A-Za-z0-9_]+)*)\.([A-Za-z_$][A-Za-z0-9_$]*)'
    r'\(([A-Za-z_][A-Za-z0-9_]*Test)\.(kt|java):([1-9][0-9]{0,6})\)\s*', re.ASCII)
# Explicit class/source pairs only; do not echo report-supplied frame methods or
# synthetic suffixes. LifecycleUiTest is declared in PR9, not included here.
TEST_SOURCES = {
    'com.fsm.RoomTest': 'RoomTest.kt',
    'com.fsm.ContractTest': 'ContractTest.kt',
    'com.fsm.ui.UiTextTest': 'UiTextTest.kt',
    'com.fsm.ui.LifecycleUiTest': 'LifecycleUiTest.kt',
}
MAX_BAD_CASES = 20
MAX_FRAMES = 3


def identifier(value):
    return value if value and len(value) <= 160 and IDENTIFIER.fullmatch(value) else 'unknown'


def emit(level, message):
    # Only fixed text, integers and allowlisted identifiers reach here.
    message = message[:1000]
    escaped = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::{level}::{escaped}')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        try:
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
                summary.write(f'- {escaped}\n')
        except (OSError, UnicodeError):
            # Fail closed, without re-entering this broken sink or exposing paths.
            print('::error::Report gate failed: cannot write step summary')
            raise SystemExit(1) from None


def diagnostic(case, tag, node):
    exception = identifier(node.get('type')) if tag != 'skipped' else 'none'
    simple = exception.rsplit('.', 1)[-1]
    category = ('assertion' if simple in ('AssertionError', 'ComparisonFailure', 'AssertionFailedError')
                else 'timeout' if simple in ('TimeoutException', 'TimeoutCancellationException', 'TestTimedOutException')
                else 'other')
    frames = []
    # No message attribute, arbitrary stack lines, system-out or nested XML text.
    for line in (node.text or '')[:65536].splitlines():
        match = FRAME.fullmatch(line)
        if not match:
            continue
        owner, _method, file, extension, number = match.groups()
        owner = owner.split('$', 1)[0]
        source = TEST_SOURCES.get(owner)
        if source != f'{file}.{extension}':
            continue
        # Emit only constants from the mapping plus the bounded numeric line.
        frame = f'{owner}({source}:{number})'
        if len(frame) <= 240 and frame not in frames:
            frames.append(frame)
        if len(frames) == MAX_FRAMES:
            break
    return (f'{tag}: {identifier(case.get("classname"))}.{identifier(case.get("name"))}; '
            f'type={exception}; category={category}; frames={" | ".join(frames) or "none"}')


def check(kind):
    for pattern, minimum in SUITES[kind]:
        files = sorted(Path('.').glob(pattern))
        if not files:
            raise ValueError(f'Missing reports: {pattern}')
        try:
            cases = [case for file in files for case in ET.parse(file).iter('testcase')]
        except (ET.ParseError, LookupError, OSError, ValueError):
            raise ValueError('Unreadable or malformed XML') from None
        tags = ('failure', 'error', 'skipped')
        counts = {tag: sum(case.find(tag) is not None for case in cases) for tag in tags}
        bad = [case for case in cases if any(case.find(tag) is not None for tag in tags)]
        emit('notice', f'{pattern}: tests={len(cases)}, failure={counts["failure"]}, '
             f'error={counts["error"]}, skips={counts["skipped"]}')
        for case in bad[:MAX_BAD_CASES]:
            for tag in tags:
                node = case.find(tag)
                if node is not None:
                    emit('error', diagnostic(case, tag, node))
        if len(bad) > MAX_BAD_CASES:
            emit('error', f'Diagnostics truncated: {len(bad) - MAX_BAD_CASES} additional bad entries; see report artifact')
        if bad or len(cases) < minimum:
            raise ValueError(f'{pattern}: {len(cases)} executed entries, '
                             f'{len(bad)} failed/error/skipped; need >= {minimum}, all passed')
        if kind == 'instrumentation':
            names = {case.get('name') for case in cases
                     if case.get('classname') == 'com.fsm.RoomTest'}
            missing = BASELINE - names
            if missing:
                raise ValueError(f'Missing baseline instrumentation cases: {sorted(missing)}')


if __name__ == '__main__':
    try:
        check(sys.argv[1])
    except (IndexError, KeyError):
        emit('error', 'Report gate failed: invalid suite argument')
        sys.exit(1)
    except (OSError, UnicodeError):
        # Parser/OS messages can contain report-controlled text or private paths.
        emit('error', 'Report gate failed: report access error')
        sys.exit(1)
    except ValueError as error:
        emit('error', f'Report gate failed: {error}')
        sys.exit(1)
