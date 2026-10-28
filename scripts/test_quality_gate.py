import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("quality_gate.py")
SPEC = importlib.util.spec_from_file_location("vibes_quality_gate", SCRIPT)
quality_gate = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(quality_gate)


class QualityGateTests(unittest.TestCase):
    def test_discovers_known_projects_and_reports_missing_root_build_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in ("Vibes_Quantum_Engine", "Vibes_Quantum_Server", "Vyron_User_App"):
                folder = root / name
                folder.mkdir()
                (folder / "Main.kt").write_text("package example\n", encoding="utf-8")
            projects = quality_gate.discover_projects(root)
            self.assertEqual({p["name"] for p in projects}, {"Vibes_Quantum_Engine", "Vibes_Quantum_Server", "Vyron_User_App"})
            self.assertTrue(all(p["build_status"] == "unavailable" for p in projects))

    def test_disposable_fixture_does_not_masquerade_as_root_build_config(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "Vibes_Quantum_Server"
            fixture = root / "sandbox" / "fixtures" / "disposable-android"
            fixture.mkdir(parents=True)
            (fixture / "build.gradle.kts").write_text("plugins {}", encoding="utf-8")
            (root / "Main.kt").write_text("package example", encoding="utf-8")
            projects = quality_gate.discover_projects(root.parent)
            self.assertEqual(len(projects), 1)
            self.assertEqual(projects[0]["root_build_markers"], [])
            self.assertEqual(projects[0]["build_status"], "unavailable")

    def test_python_syntax_reports_location_without_executing_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "safe.py").write_text("value = 3\n", encoding="utf-8")
            (root / "broken.py").write_text("def bad(:\n    pass\n", encoding="utf-8")
            report = quality_gate.inspect_python(root)
            self.assertEqual(report["status"], "failed")
            self.assertEqual(report["files_checked"], 1)
            self.assertEqual(report["findings"][0]["path"], "broken.py")

    def test_secret_scan_never_copies_secret_value_to_report(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            secret = "ghp_" + "A" * 36
            (root / "config.py").write_text(f'TOKEN = "{secret}"\n', encoding="utf-8")
            report = quality_gate.scan_secrets(root)
            self.assertEqual(report["status"], "failed")
            self.assertNotIn(secret, json.dumps(report))
            self.assertEqual(report["findings"][0]["path"], "config.py")

    def test_example_credentials_are_not_reported(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "worker.env.example").write_text('API_KEY="ghp_' + "A" * 36 + '"\n', encoding="utf-8")
            report = quality_gate.scan_secrets(root)
            self.assertEqual(report["status"], "passed")

    def test_unavailable_build_or_skipped_test_never_marks_ready(self):
        passed = {name: {"status": "passed"} for name in ("python_syntax", "json_syntax", "shell_syntax", "secret_scan", "python_unit_tests")}
        projects = [{"path": "Vibes_Quantum_Engine", "build_status": "unavailable"}]
        self.assertEqual(quality_gate.compose_status(passed, projects), "NOT_READY")
        passed["python_unit_tests"] = {"status": "skipped"}
        projects[0]["build_status"] = "present"
        self.assertEqual(quality_gate.compose_status(passed, projects), "NOT_READY")


if __name__ == "__main__":
    unittest.main()
