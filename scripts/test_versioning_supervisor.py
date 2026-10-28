import tempfile, unittest
from pathlib import Path
from versioning_supervisor import VersionStore, ContinuousSupervisor

class VersioningSupervisorTests(unittest.TestCase):
    def test_snapshot_diff_and_admin_gated_rollback(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); project=root/"project"; project.mkdir()
            (project/"Main.kt").write_text("val x = 1")
            store=VersionStore(root/"state")
            good=store.snapshot(project, "baseline", known_good=True)
            (project/"Main.kt").write_text("val x = 2")
            (project/"New.kt").write_text("class New")
            candidate=store.snapshot(project, "candidate")
            diff=store.compare(good["id"], candidate["id"])
            self.assertEqual(diff["changed"], ["Main.kt"])
            self.assertEqual(diff["added"], ["New.kt"])
            with self.assertRaises(PermissionError):
                store.rollback(good["id"], root/"restore-no-auth", False, True)
            with self.assertRaises(PermissionError):
                store.rollback(good["id"], root/"restore-no-regression", True, False)
            result=store.rollback(good["id"], root/"restored", True, True)
            self.assertTrue(result["verified"])
            self.assertEqual((root/"restored"/"Main.kt").read_text(), "val x = 1")

    def test_supervisor_cycle_budget_failure_and_persistent_emergency_stop(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); stop=root/"EMERGENCY_STOP"; state=root/"state.json"
            sup=ContinuousSupervisor(state, stop, max_cycles=4, max_failures=2, budget_limit=5)
            outcomes=iter([{"status":"READY_FOR_REVIEW","cost":2},{"status":"READY_FOR_REVIEW","cost":4}])
            result=sup.run(lambda n: next(outcomes), admin_authorized=True, interval_seconds=0, sleep=lambda _: None)
            self.assertEqual(result["status"], "BUDGET_LIMIT")
            self.assertTrue(state.exists())
            with self.assertRaises(PermissionError): sup.stop(False)
            sup.stop(True)
            result=sup.run(lambda n: {"status":"READY_FOR_REVIEW"}, admin_authorized=True)
            self.assertEqual(result["status"], "EMERGENCY_STOP")

    def test_supervisor_requires_admin_and_bounds_failures(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            sup=ContinuousSupervisor(root/"state.json", root/"stop", max_cycles=5, max_failures=2)
            with self.assertRaises(PermissionError):
                sup.run(lambda n: {}, admin_authorized=False)
            result=sup.run(lambda n: {"status":"FAILED"}, admin_authorized=True, interval_seconds=0, sleep=lambda _: None)
            self.assertEqual(result["status"], "FAILURE_THRESHOLD")
            self.assertEqual(result["cycles"], 2)

if __name__ == "__main__": unittest.main()
