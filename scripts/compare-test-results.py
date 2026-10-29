#!/usr/bin/env python3
"""Compare JUnit evidence without converting an upstream failure into a passing test."""
from pathlib import Path
import argparse
import json
import re
import xml.etree.ElementTree as ET


def read_results(folder):
    cases = {}
    for path in Path(folder).rglob('TEST-*.xml'):
        root = ET.parse(path).getroot()
        for case in root.iter('testcase'):
            key = case.get('classname', '') + ' :: ' + case.get('name', '')
            failure = case.find('failure')
            if failure is None:
                failure = case.find('error')
            status = 'failed' if failure is not None else 'skipped' if case.find('skipped') is not None else 'passed'
            message = '' if failure is None else re.sub(r'#\d+', '#*', failure.get('message', ''))
            cases[key] = {'status': status, 'type': '' if failure is None else failure.get('type', ''), 'message': message}
    if not cases:
        raise ValueError('No JUnit testcase evidence found: ' + str(folder))
    return cases


def compare(baseline, modified):
    failed = {k for k,v in modified.items() if v['status'] == 'failed'}
    inherited = {k for k in failed if baseline.get(k) == modified[k]}
    regressions = failed - inherited
    missing = set(baseline) - set(modified)
    new_skips = {k for k,v in modified.items() if v['status'] == 'skipped' and baseline.get(k, {}).get('status') != 'skipped'}
    return {
        'baseline_tests': len(baseline), 'modified_tests': len(modified),
        'inherited_failures': sorted(inherited), 'new_or_changed_failures': sorted(regressions),
        'missing_tests': sorted(missing), 'new_skips': sorted(new_skips),
        'fixed_failures': sorted(k for k,v in baseline.items() if v['status'] == 'failed' and modified.get(k, {}).get('status') == 'passed'),
        'regression_check_passed': not (regressions or missing or new_skips),
        'full_suite_passed': not failed and not missing and not new_skips,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('baseline')
    parser.add_argument('modified')
    args = parser.parse_args()
    result = compare(read_results(args.baseline), read_results(args.modified))
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result['regression_check_passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
