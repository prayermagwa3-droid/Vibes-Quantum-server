"""Regression tests for deterministic, non-executing engineering candidate audits."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("workspace_runner.py")


def invoke(*args: str) -> tuple[int, dict]:
    env = os.environ.copy()
    env.pop("VIBES_WORKSPACE_SANDBOX_IMAGE", None)
    env.pop("VIBES_WORKSPACE_SANDBOX_RUNTIME", None)
    proc = subprocess.run(
        [sys.executable, str(SCRIPT), *args], env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=20,
    )
    try:
        result = json.loads(proc.stdout.strip().splitlines()[-1])
    except Exception as error:
        raise AssertionError(f"Audit did not return JSON: {proc.stdout}") from error
    return proc.returncode, result


class EngineeringAuditTests(unittest.TestCase):
    def setup_projects(self, root: Path) -> tuple[Path, Path, Path]:
        baseline = root / "baseline"
        candidate = root / "candidate"
        artifacts = root / "artifacts"
        baseline.mkdir()
        candidate.mkdir()
        (baseline / "main.py").write_text("def answer():\n    return 1\n", encoding="utf-8")
        (candidate / "main.py").write_text("def answer():\n    return 2\n", encoding="utf-8")
        (baseline / "test_main.py").write_text("def test_answer():\n    assert True\n", encoding="utf-8")
        (candidate / "test_main.py").write_text("def test_answer():\n    assert True\n", encoding="utf-8")
        return baseline, candidate, artifacts

    def run_audit(self, baseline: Path, candidate: Path, artifacts: Path) -> tuple[int, dict]:
        return invoke("audit", "--baseline", str(baseline), "--candidate", str(candidate), "--artifact-dir", str(artifacts))

    def test_valid_candidate_generates_diff_report_and_hashes(self):
        with tempfile.TemporaryDirectory() as temp:
            baseline, candidate, artifacts = self.setup_projects(Path(temp))
            code, result = self.run_audit(baseline, candidate, artifacts)
            self.assertEqual(code, 0, result)
            self.assertEqual(result["status"], "AUDIT_PASSED")
            self.assertEqual(result["counts"]["changed"], 1)
            self.assertTrue((artifacts / "candidate-review.diff.txt").is_file())
            report = json.loads((artifacts / "candidate-audit.json").read_text(encoding="utf-8"))
            self.assertEqual(report["changedFiles"][0]["path"], "main.py")
            self.assertTrue(report["changedFiles"][0]["baselineSha256"])
            self.assertTrue(report["changedFiles"][0]["candidateSha256"])

    def test_invalid_python_and_json_block_candidate(self):
        with tempfile.TemporaryDirectory() as temp:
            baseline, candidate, artifacts = self.setup_projects(Path(temp))
            (candidate / "main.py").write_text("def broken(:\n  pass\n", encoding="utf-8")
            (baseline / "config.json").write_text('{"mode":"safe"}\n', encoding="utf-8")
            (candidate / "config.json").write_text('{"mode":}\n', encoding="utf-8")
            code, result = self.run_audit(baseline, candidate, artifacts)
            self.assertNotEqual(code, 0)
            self.assertEqual(result["status"], "AUDIT_BLOCKED")
            codes = {item["code"] for item in result["blockers"]}
            self.assertIn("SYNTAX_ERROR", codes)

    def test_embedded_token_blocks_and_never_leaks_into_diff_or_report(self):
        with tempfile.TemporaryDirectory() as temp:
            baseline, candidate, artifacts = self.setup_projects(Path(temp))
            secret = "ghp_" + "A" * 36
            (candidate / "main.py").write_text(f'TOKEN = "{secret}"\n', encoding="utf-8")
            code, result = self.run_audit(baseline, candidate, artifacts)
            self.assertNotEqual(code, 0)
            self.assertEqual(result["status"], "AUDIT_BLOCKED")
            diff = (artifacts / "candidate-review.diff.txt").read_text(encoding="utf-8")
            report = (artifacts / "candidate-audit.json").read_text(encoding="utf-8")
            self.assertNotIn(secret, diff)
            self.assertNotIn(secret, report)
            self.assertTrue(any(item["code"] == "POSSIBLE_CREDENTIAL" for item in result["blockers"]))

    def test_multiline_private_key_is_redacted_across_diff_lines(self):
        with tempfile.TemporaryDirectory() as temp:
            baseline, candidate, artifacts = self.setup_projects(Path(temp))
            begin_marker = "-----BEGIN RSA " + "PRIVATE KEY-----"
            end_marker = "-----END RSA " + "PRIVATE KEY-----"
            private_key = begin_marker + "\n" + "SECRETKEYMATERIAL1234567890\n" + end_marker
            (candidate / "main.py").write_text("KEY = \"" + private_key + "\"\n", encoding="utf-8")
            code, result = self.run_audit(baseline, candidate, artifacts)
            self.assertNotEqual(code, 0)
            diff = (artifacts / "candidate-review.diff.txt").read_text(encoding="utf-8")
            report = (artifacts / "candidate-audit.json").read_text(encoding="utf-8")
            self.assertNotIn("SECRETKEYMATERIAL1234567890", diff)
            self.assertNotIn("SECRETKEYMATERIAL1234567890", report)
            self.assertIn("PRIVATE KEY REDACTED", diff)

    def test_sensitive_build_file_is_flagged_for_review_not_misreported_as_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            baseline, candidate, artifacts = self.setup_projects(root)
            (baseline / "build.gradle.kts").write_text("plugins {}\n", encoding="utf-8")
            (candidate / "build.gradle.kts").write_text("plugins { id(\"java\") }\n", encoding="utf-8")
            code, result = self.run_audit(baseline, candidate, artifacts)
            self.assertEqual(code, 0, result)
            self.assertEqual(result["status"], "AUDIT_REVIEW_REQUIRED")
            self.assertTrue(any(item["code"] == "SENSITIVE_AREA_CHANGED" for item in result["reviewItems"]))

    def test_symlinks_are_blocked_without_following_them(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            baseline, candidate, artifacts = self.setup_projects(root)
            outside = root / "outside.txt"
            outside.write_text("do not change\n", encoding="utf-8")
            (candidate / "link.txt").symlink_to(outside)
            code, result = self.run_audit(baseline, candidate, artifacts)
            self.assertNotEqual(code, 0)
            self.assertTrue(any(item["code"] == "SYMLINK_IN_CANDIDATE" for item in result["blockers"]))
            self.assertEqual(outside.read_text(encoding="utf-8"), "do not change\n")

    def test_no_change_is_not_accepted_as_a_candidate(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            baseline = root / "baseline"
            candidate = root / "candidate"
            artifacts = root / "artifacts"
            baseline.mkdir(); candidate.mkdir()
            (baseline / "main.txt").write_text("same\n", encoding="utf-8")
            (candidate / "main.txt").write_text("same\n", encoding="utf-8")
            code, result = self.run_audit(baseline, candidate, artifacts)
            self.assertNotEqual(code, 0)
            self.assertTrue(any(item["code"] == "NO_CHANGES" for item in result["blockers"]))

    def test_artifact_bundle_contains_audit_diff_and_review_dossier(self):
        with tempfile.TemporaryDirectory() as temp:
            baseline, candidate, artifacts = self.setup_projects(Path(temp))
            code, result = self.run_audit(baseline, candidate, artifacts / "review")
            self.assertEqual(code, 0, result)
            (artifacts / "review" / "release-dossier.txt").write_text("Human review required; not deployed.\n", encoding="utf-8")
            bundle = Path(temp) / "build-artifacts.zip"
            code, packed = invoke("artifact-package", "--artifact-dir", str(artifacts), "--output", str(bundle))
            self.assertEqual(code, 0, packed)
            import zipfile
            with zipfile.ZipFile(bundle) as archive:
                names = set(archive.namelist())
            self.assertIn("review/candidate-audit.json", names)
            self.assertIn("review/candidate-review.diff.txt", names)
            self.assertIn("review/release-dossier.txt", names)

    def test_audit_artifacts_must_not_be_inside_candidate(self):
        with tempfile.TemporaryDirectory() as temp:
            baseline, candidate, _ = self.setup_projects(Path(temp))
            code, result = invoke("audit", "--baseline", str(baseline), "--candidate", str(candidate), "--artifact-dir", str(candidate / "audit"))
            self.assertNotEqual(code, 0)
            self.assertIn("outside both source trees", result["message"])


if __name__ == "__main__":
    unittest.main()
