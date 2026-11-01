import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('summary', Path(__file__).with_name('print-summary.py'))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class SummaryTest(unittest.TestCase):
    def test_rate_excludes_final_drain(self):
        text = summary.production_summary({
            'seconds': 10, 'produced': 1000,
            'counts': {'completedRecords': 900, 'newRecords': 100, 'failedRecords': 0},
        })
        self.assertIn('90.00 records/s', text)
        self.assertIn('Pending at producer stop | 100', text)

    def test_counts_must_reconcile(self):
        with self.assertRaises(ValueError):
            summary.production_summary({
                'seconds': 10, 'produced': 1000,
                'counts': {'completedRecords': 1001, 'newRecords': 0, 'failedRecords': 0},
            })

    def test_drained_run_can_still_fail_capacity(self):
        ordering = {'valid': True, 'violations': 0, 'missingCompletions': 0, 'checkedRecords': 1000}
        for compliant, sustainable in [(False, True), (True, False), (False, False)]:
            with self.subTest(compliant=compliant, sustainable=sustainable):
                with self.assertRaises(ValueError):
                    summary.validate_result({
                        'status': 'VALID', 'expectedRecords': 1000,
                        'steadyState': {'producerRateCompliant': compliant, 'sustainable': sustainable},
                    }, ordering)

    def test_full_ordering_audit_is_required(self):
        for valid, checked in [(False, 1000), (True, 999)]:
            with self.subTest(valid=valid, checked=checked):
                with self.assertRaises(ValueError):
                    summary.validate_result({'status': 'VALID', 'expectedRecords': 1000},
                                            {'valid': valid, 'violations': 0, 'missingCompletions': 0, 'checkedRecords': checked})

    def test_healthy_steady_state_and_backlog_runs_pass(self):
        parameters = {'status': 'VALID', 'expectedRecords': 1000}
        ordering = {'valid': True, 'violations': 0, 'missingCompletions': 0, 'checkedRecords': 1000}
        summary.validate_result(parameters, ordering)
        parameters['steadyState'] = {'producerRateCompliant': True, 'sustainable': True}
        summary.validate_result(parameters, ordering)


if __name__ == '__main__':
    unittest.main()
