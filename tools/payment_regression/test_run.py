import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
from run import collect, run

class EvidenceTest(unittest.TestCase):
    def test_dry_run_never_executes_commands(self):
        with patch('run.subprocess.run') as command, patch('run.subprocess.check_output') as output:
            report=run(False)
        command.assert_not_called();output.assert_not_called()
        self.assertEqual(report['overall'],'NOT_RUN')
        self.assertFalse(report['productionAcceptance'])

    def collect(self, xml, required=None):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp)/'TEST-fixture.xml').write_text(xml)
            return collect(tmp, required or {})

    def test_required_case_is_executed_and_mapped(self):
        result=self.collect('<testsuite><testcase name="critical"/></testsuite>', {'critical':'race'})
        self.assertEqual(result,{'tests':1,'cases':{'race':'PASS'}})

    def test_missing_case_cannot_pass(self):
        with self.assertRaises(ValueError):
            self.collect('<testsuite><testcase name="other"/></testsuite>', {'critical':'race'})

    def test_failed_skipped_and_errored_cases_cannot_pass(self):
        for tag in ('failure','error','skipped'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                self.collect('<testsuite><testcase name="critical"><'+tag+'/></testcase></testsuite>')

    def test_empty_reports_cannot_pass(self):
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(ValueError): collect(tmp,{})

    def test_duplicate_case_names_do_not_hide_failure(self):
        with self.assertRaises(ValueError):
            self.collect('<testsuite><testcase name="race"><failure/></testcase><testcase name="race"/></testsuite>')

if __name__=='__main__':unittest.main()
