import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[2] / 'scripts/compare-test-results.py'
spec = importlib.util.spec_from_file_location('result_comparison', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ResultComparisonTests(unittest.TestCase):
    passed = {'status': 'passed', 'type': '', 'message': ''}
    failed = {'status': 'failed', 'type': 'AssertionError', 'message': 'fixture'}
    def test_baseline_failure_never_becomes_full_suite_success(self):
        result = module.compare({'existing': self.failed}, {'existing': self.failed, 'new': self.passed})
        self.assertTrue(result['regression_check_passed'])
        self.assertFalse(result['full_suite_passed'])
    def test_changed_failure_on_same_test_is_a_regression(self):
        different = dict(self.failed, message='different assertion')
        result = module.compare({'existing': self.failed}, {'existing': different})
        self.assertFalse(result['regression_check_passed'])
    def test_removed_test_cannot_hide_failure(self):
        result = module.compare({'existing': self.failed}, {'new': self.passed})
        self.assertFalse(result['regression_check_passed'])
        self.assertEqual(['existing'], result['missing_tests'])
