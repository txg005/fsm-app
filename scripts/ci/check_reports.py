#!/usr/bin/env python3
"""Fail closed on absent, failing, skipped, or incomplete JUnit XML suites."""
import os
from pathlib import Path
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


def check(kind):
    for pattern, minimum in SUITES[kind]:
        files = sorted(Path('.').glob(pattern))
        if not files:
            raise ValueError(f'Missing reports: {pattern}')
        cases = [case for file in files for case in ET.parse(file).iter('testcase')]
        bad = [case for case in cases if any(case.find(tag) is not None
                                           for tag in ('failure', 'error', 'skipped'))]
        if bad or len(cases) < minimum:
            raise ValueError(f'{pattern}: {len(cases)} executed entries, '
                             f'{len(bad)} failed/error/skipped; need >= {minimum}, all passed; '
                             f'bad cases: {[case.get("classname", "") + "." + case.get("name", "") for case in bad]}')
        if kind == 'instrumentation':
            names = {case.get('name') for case in cases
                     if case.get('classname') == 'com.fsm.RoomTest'}
            missing = BASELINE - names
            if missing:
                raise ValueError(f'Missing baseline instrumentation cases: {sorted(missing)}')
        message = f'{pattern}: {len(cases)} tests, 0 failures/errors/skips'
        print(message)
        print(f'::notice::{message}')
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
                summary.write(f'- {message}\n')


if __name__ == '__main__':
    try:
        check(sys.argv[1])
    except (IndexError, KeyError, ValueError, ET.ParseError) as error:
        message = str(error).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print(f'::error::{message}')
        sys.exit(f'Report gate failed: {error}')
