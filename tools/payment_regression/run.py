"""SCRUM-109: reproducible local regression evidence, never production sign-off."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
CASES = {
    'slot-hold-engine': {
        'onlyOneOfConcurrentClientsWins': 'last-slot-race',
        'conflictNeverWritesPartialSelectionOrToken': 'atomic-hold-selection',
        'releaseNeverDeletesSlotNowOwnedBySomeoneElse': 'release-ownership',
        'expiredKeyCanBeAcquiredAgainWithoutCleanupJob': 'hold-expiry',
    },
    'booking-service': {
        'duplicateConcurrentCancellationCreatesOneOutboxEvent': 'cancellation-idempotency',
        'outboxFailureRollsBackBookingAndReservation': 'cancellation-atomicity',
        'paidCancellationPreservesFinancialStateAndSlotHistory': 'paid-cancellation',
    },
    'payment-service': {
        'spoofedImagesAndOversizedFilesFailBeforeStorage': 'oversized-receipt',
        'expiryInFlightCannotSubmitOrEmitEvent': 'expiry-during-upload',
        'competingUploadsCreateExactlyOnePaymentAndSubmissionEvent': 'concurrent-submission',
        'confirmIsIdempotentAndRejectCannotOverwriteConfirmation': 'duplicate-confirmation',
        'parallelConfirmAndRejectOnlyOneDecisionWins': 'decision-race',
        'uploadRacingExpiryCannotCancelAnAcceptedReceipt': 'upload-expiry-race',
        'customerHistoryRejectsSpoofedHeadersAndOtherOwners': 'customer-isolation',
        'foreignBranchAndMissingReasonCannotChangePayment': 'branch-isolation',
        'concurrentRefundRequestsRemainUnique': 'refund-idempotency',
    },
}

def collect(directory, required):
    results = {}
    count = 0
    for file in sorted(Path(directory).glob('TEST-*.xml')):
        suite = ET.parse(file).getroot()
        for case in suite.iter('testcase'):
            name = case.attrib['name']
            failed = any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped'))
            count += 1
            results[name] = not failed and results.get(name, True)
    if not count:
        raise ValueError('No executed test evidence')
    if any(not value for value in results.values()):
        raise ValueError('Failed or skipped tests cannot establish regression evidence')
    missing = set(required) - results.keys()
    if missing:
        raise ValueError('Required regression cases did not run: ' + ', '.join(sorted(missing)))
    return {'tests': count, 'cases': {label: 'PASS' for label in required.values() if label}}

def run(execute, root=ROOT):
    report = {'scope': 'local_service_regression', 'productionAcceptance': False,
              'overall': 'NOT_RUN', 'modules': {},
              'externalGates': {'productionDeployment': 'NOT_RUN', 'productionSmoke': 'NOT_RUN',
                                'androidNetworkLatency': 'NOT_RUN', 'fullCourtReservation': 'NOT_RUN',
                                'realProviderReceiptDelivery': 'NOT_RUN'}}
    if not execute:
        return report
    report['revision'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    destination = root / 'target/payment-regression'
    destination.mkdir(parents=True, exist_ok=True)
    for module, required in CASES.items():
        started = time.monotonic()
        try:
            # Clean is essential: reports from an older revision cannot count as current evidence.
            with (destination / (module + '.log')).open('w') as log:
                result = subprocess.run(['sh', 'mvnw', '-B', '-ntp', 'clean', 'verify'],
                                        cwd=root/module, stdout=log, stderr=subprocess.STDOUT, timeout=900)
            if result.returncode:
                raise ValueError('Maven failed; inspect protected local log')
            evidence = collect(root/module/'target/surefire-reports', required)
            report['modules'][module] = {'status': 'PASS', **evidence}
        except (subprocess.TimeoutExpired, OSError, ValueError, ET.ParseError):
            report['modules'][module] = {'status': 'FAIL'}
        report['modules'][module]['seconds'] = round(time.monotonic()-started, 2)
    report['overall'] = 'PASS' if all(x['status']=='PASS' for x in report['modules'].values()) else 'FAIL'
    return report

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true', help='run local Maven/Testcontainers suites')
    args = parser.parse_args()
    report = run(args.execute)
    destination = ROOT / 'target/payment-regression'
    destination.mkdir(parents=True, exist_ok=True)
    (destination/'report.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report['overall']=='PASS' else 2)
