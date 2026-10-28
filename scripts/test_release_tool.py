import importlib.util, json, tempfile, unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("release_tool.py")
spec = importlib.util.spec_from_file_location("release_tool", SCRIPT)
rt = importlib.util.module_from_spec(spec); spec.loader.exec_module(rt)

class ReleaseToolTests(unittest.TestCase):
    def test_snapshot_restore_and_integrity(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)/"root"; root.mkdir(); (root/"state.json").write_text('{"v":1}')
            snap=Path(td)/"snapshot"
            rt.create_snapshot([root/"state.json"],snap,root)
            self.assertEqual(rt.verify_snapshot(snap),[])
            (root/"state.json").write_text('{"v":2}')
            self.assertEqual(rt.restore_snapshot(snap,root),1)
            self.assertEqual((root/"state.json").read_text(),'{"v":1}')
    def test_snapshot_detects_tamper(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)/"root"; root.mkdir(); (root/"data").write_text("safe")
            snap=Path(td)/"snap"; rt.create_snapshot([root/"data"],snap,root)
            (snap/"data").write_text("tampered")
            self.assertTrue(rt.verify_snapshot(snap))
    def test_path_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(ValueError): rt.safe_relative(Path(td)/"safe",Path("../outside"))
    def test_sqlite_migration_and_one_step_rollback(self):
        with tempfile.TemporaryDirectory() as td:
            d=Path(td); db=d/"test.sqlite"; mig=Path(__file__).parents[1]/"migrations"
            up=rt.migrate(db,mig,"up",d/"backups")
            self.assertIn("0001_release_ledger.up.sql",up)
            con=__import__('sqlite3').connect(db)
            self.assertIsNotNone(con.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='release_metadata'").fetchone()); con.close()
            down=rt.migrate(db,mig,"down",d/"backups")
            self.assertIn("0001_release_ledger.down.sql",down)
            self.assertTrue(list((d/"backups").glob("*.sqlite3")))
    def test_gate_denies_missing_critical_checks_and_regression(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td); (root/"release").mkdir()
            (root/"release/release-manifest.json").write_text(json.dumps({"candidate_version":"test","source_revision":"REQUIRED_AT_BUILD","created_utc":"SET_BY_RELEASE_PIPELINE"}))
            (root/"release/release-policy.json").write_text(json.dumps({"critical_checks":["source_manifest","unit_tests","integration_tests","security_tests","evaluation_tests","staging_smoke","baseline_comparison","rollback_drill"]}))
            manifest=root/"release/artifact-manifest.json"; rt.build_manifest(root,manifest)
            checks={k:"passed" for k in ["source_manifest","unit_tests","integration_tests","security_tests","evaluation_tests","staging_smoke","baseline_comparison","rollback_drill"]}
            report={"checks":checks,"baseline_comparison":{"quality_score":{"baseline":90,"candidate":85},"p95_latency_ms":{"baseline":100,"candidate":110},"cost_per_1000_requests":{"baseline":1.0,"candidate":1.05}}}
            rp=root/"report.json"; rp.write_text(json.dumps(report))
            ok,result=rt.gate(rp,manifest,root)
            self.assertFalse(ok)
            self.assertEqual(result["baseline_comparison"]["quality_score"]["status"],"failed")
    def test_gate_accepts_complete_measured_candidate(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td); (root/"release").mkdir()
            (root/"release/release-manifest.json").write_text(json.dumps({"candidate_version":"test","source_revision":"REQUIRED_AT_BUILD","created_utc":"SET_BY_RELEASE_PIPELINE"}))
            (root/"release/release-policy.json").write_text(json.dumps({"critical_checks":["source_manifest","unit_tests","integration_tests","security_tests","evaluation_tests","staging_smoke","baseline_comparison","rollback_drill"]}))
            manifest=root/"release/artifact-manifest.json"; rt.build_manifest(root,manifest)
            checks={k:"passed" for k in ["source_manifest","unit_tests","integration_tests","security_tests","evaluation_tests","staging_smoke","baseline_comparison","rollback_drill"]}
            report={"checks":checks,"baseline_comparison":{"quality_score":{"baseline":90,"candidate":90},"p95_latency_ms":{"baseline":100,"candidate":110},"cost_per_1000_requests":{"baseline":1.0,"candidate":1.05}}}
            rp=root/"report.json"; rp.write_text(json.dumps(report))
            ok,result=rt.gate(rp,manifest,root)
            self.assertTrue(ok, result)
            self.assertEqual(result["decision"],"ELIGIBLE_FOR_HUMAN_APPROVAL")
    def test_manifest_excludes_generated_quality_gate_report(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td); (root/"release").mkdir()
            (root/"release/release-manifest.json").write_text(json.dumps({"candidate_version":"test","source_revision":"REQUIRED_AT_BUILD","created_utc":"SET_BY_RELEASE_PIPELINE"}))
            (root/"release/quality-gate-report.json").write_text(json.dumps({"overall":"NOT_READY"}))
            manifest=rt.build_manifest(root,root/"release/artifact-manifest.json")
            self.assertNotIn("release/quality-gate-report.json",manifest["artifact_integrity"]["files"])

    def test_feature_registry_all_optional_disabled(self):
        data=json.loads((SCRIPT.parents[1]/"release/phase20-feature-review.json").read_text())
        self.assertTrue(data["features"])
        self.assertTrue(all(f["enabled"] is False for f in data["features"]))
        for f in data["features"]:
            for key in ("problem_statement","threat_model","cost_estimate","benchmark","rollback","gate"):
                self.assertIn(key,f)

if __name__ == "__main__": unittest.main()
