"""Host-safe tests for the staged workspace runner; no source project is executed."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("workspace_runner.py")


def invoke(*args: str, env: dict[str, str] | None = None) -> tuple[int, dict]:
    merged = os.environ.copy()
    merged.pop("VIBES_WORKSPACE_SANDBOX_IMAGE", None)
    merged.pop("VIBES_WORKSPACE_SANDBOX_RUNTIME", None)
    if env:
        merged.update(env)
    result = subprocess.run(
        [sys.executable, str(SCRIPT), *args], text=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=merged, timeout=20,
    )
    try:
        payload = json.loads(result.stdout.strip().splitlines()[-1])
    except Exception as error:  # pragma: no cover - diagnostic fallback
        raise AssertionError(f"Runner did not return JSON: {result.stdout}") from error
    return result.returncode, payload


class WorkspaceRunnerTests(unittest.TestCase):
    def test_archive_extraction_blocks_zip_slip_and_redacts_tokens(self):
        with tempfile.TemporaryDirectory() as raw:
            temp = Path(raw)
            archive = temp / "source.zip"
            project = temp / "unpacked"
            with zipfile.ZipFile(archive, "w") as out:
                out.writestr("sample/settings.gradle.kts", "rootProject.name = \\\"Safe\\\"\n")
                out.writestr("sample/src/Main.kt", 'val token = "ghp_abcdefghijklmnopqrstuvwxyz1234567890"\n')
                out.writestr("../outside.txt", "not allowed")
            code, result = invoke("inspect", "--archive", str(archive), "--project", str(project))
            self.assertEqual(code, 0, result)
            self.assertTrue(result["ok"])
            self.assertEqual(result["projectRoot"], "sample")
            self.assertTrue(any("Unsafe archive path" in w for w in result["warnings"]))
            self.assertFalse((temp / "outside.txt").exists())
            source_excerpt = " ".join(item["excerpt"] for item in result["excerpts"])
            self.assertNotIn("ghp_abcdefghijklmnopqrstuvwxyz1234567890", source_excerpt)
            self.assertEqual((project / "sample" / "settings.gradle.kts").is_file(), True)

    def test_staged_patch_applies_and_path_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            project = root / "project"
            project.mkdir()
            (project / "src.txt").write_text("first\nold\n", encoding="utf-8")
            patch = root / "good.diff"
            patch.write_text("--- a/src.txt\n+++ b/src.txt\n@@ -1,2 +1,2 @@\n first\n-old\n+new\n", encoding="utf-8")
            code, result = invoke("apply", "--project", str(project), "--patch", str(patch))
            self.assertEqual(code, 0, result)
            self.assertEqual((project / "src.txt").read_text(encoding="utf-8"), "first\nnew\n")

            bad = root / "bad.diff"
            bad.write_text("--- a/../../outside.txt\n+++ b/../../outside.txt\n@@ -1 +1 @@\n-old\n+new\n", encoding="utf-8")
            code, result = invoke("apply", "--project", str(project), "--patch", str(bad))
            self.assertNotEqual(code, 0)
            self.assertIn("traversal", result["message"].lower())
            self.assertFalse((root.parent / "outside.txt").exists())

    def test_project_code_is_not_executed_without_a_sandbox(self):
        with tempfile.TemporaryDirectory() as raw:
            project = Path(raw)
            (project / "pyproject.toml").write_text("[project]\nname='fixture'\nversion='0.0.1'\n", encoding="utf-8")
            marker = project / "executed.txt"
            (project / "test_fixture.py").write_text(
                f"from pathlib import Path\nPath({str(marker)!r}).write_text('bad')\n", encoding="utf-8"
            )
            code, result = invoke("test", "--project", str(project), "--timeout", "30")
            self.assertEqual(code, 0, result)
            self.assertEqual(result["status"], "SANDBOX_NOT_CONFIGURED")
            self.assertFalse(marker.exists(), "runner must fail closed instead of executing project code on the host")

    def test_clone_makes_separate_symlink_free_copy_and_keeps_source_unchanged(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            source = root / "source"
            source.mkdir()
            (source / "settings.gradle.kts").write_text("rootProject.name = \"Safe\"\n", encoding="utf-8")
            (source / "local.properties").write_text("sdk.dir=/private/sdk\n", encoding="utf-8")
            (source / "build").mkdir()
            (source / "build" / "generated.txt").write_text("cache", encoding="utf-8")
            output = root / "copy"
            code, result = invoke("clone", "--project", str(source), "--output", str(output))
            self.assertEqual(code, 0, result)
            self.assertTrue(result["ok"])
            self.assertTrue((output / "settings.gradle.kts").is_file())
            self.assertFalse((output / "local.properties").exists())
            self.assertFalse((output / "build").exists())
            self.assertTrue((source / "local.properties").is_file())
            (output / "settings.gradle.kts").write_text("changed", encoding="utf-8")
            self.assertIn("Safe", (source / "settings.gradle.kts").read_text(encoding="utf-8"))

    def test_patch_rejects_symlink_traversal_before_git_apply(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            project = root / "project"
            outside = root / "outside"
            project.mkdir()
            outside.mkdir()
            (outside / "victim.txt").write_text("safe\n", encoding="utf-8")
            (project / "jump").symlink_to(outside, target_is_directory=True)
            patch = root / "symlink.diff"
            patch.write_text("--- a/jump/victim.txt\n+++ b/jump/victim.txt\n@@ -1 +1 @@\n-safe\n+changed\n", encoding="utf-8")
            code, result = invoke("apply", "--project", str(project), "--patch", str(patch))
            self.assertNotEqual(code, 0)
            self.assertIn("symbolic link", result["message"].lower())
            self.assertEqual((outside / "victim.txt").read_text(encoding="utf-8"), "safe\n")

    def test_node_tests_require_lockfile_and_never_execute_on_host(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / "package.json").write_text('{"scripts":{"test":"node -e \"process.exit(0)\""}}', encoding="utf-8")
            code, result = invoke("test", "--project", str(root), "--timeout", "30")
            self.assertEqual(code, 0, result)
            self.assertEqual(result["status"], "TESTS_UNAVAILABLE")
            (root / "package-lock.json").write_text('{"name":"fixture","lockfileVersion":3,"packages":{}}', encoding="utf-8")
            code, result = invoke("test", "--project", str(root), "--timeout", "30")
            self.assertEqual(code, 0, result)
            self.assertEqual(result["status"], "SANDBOX_NOT_CONFIGURED")

    def test_candidate_packaging_blocks_embedded_credentials(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            project = root / "project"
            project.mkdir()
            (project / "src.kt").write_text('val apiKey = "sk-ABCDEFGHIJKLMNOPQRST1234567890"\n', encoding="utf-8")
            output = root / "candidate.zip"
            code, result = invoke("package", "--project", str(project), "--output", str(output))
            self.assertNotEqual(code, 0)
            self.assertIn("credential", result["message"].lower())
            self.assertFalse(output.exists())

    def test_candidate_packaging_excludes_secrets_and_caches(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            project = root / "project"
            project.mkdir()
            (project / "src.kt").write_text("val ready = true\n", encoding="utf-8")
            (project / "google-services.json").write_text("credential", encoding="utf-8")
            (project / ".gradle").mkdir()
            (project / ".gradle" / "cache.bin").write_bytes(b"cache")
            output = root / "candidate.zip"
            code, result = invoke("package", "--project", str(project), "--output", str(output))
            self.assertEqual(code, 0, result)
            self.assertTrue(result["ok"])
            with zipfile.ZipFile(output) as archive:
                names = set(archive.namelist())
            self.assertEqual(names, {"src.kt"})


if __name__ == "__main__":
    unittest.main()

# App Builder profile and output persistence contracts. These tests inspect fixtures only;
# they do not run any project build scripts on the host.
class UniversalBuilderProfileTests(unittest.TestCase):
    def test_android_profile_detects_kotlin_manifest_dependencies_and_safe_commands(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            archive = root / "android.zip"
            staged = root / "staged"
            with zipfile.ZipFile(archive, "w") as out:
                out.writestr("Demo/settings.gradle.kts", "rootProject.name = \"Demo\"\ninclude(\":app\")")
                out.writestr("Demo/build.gradle.kts", "plugins { id(\"com.android.application\") version \"8.0.0\" apply false }")
                out.writestr("Demo/app/build.gradle.kts", "dependencies { implementation(\"androidx.core:core-ktx:1.12.0\") }")
                out.writestr("Demo/app/src/main/AndroidManifest.xml", "<manifest package=\"test.app\" />")
                out.writestr("Demo/app/src/main/kotlin/test/MainActivity.kt", "class MainActivity")
            code, result = invoke("inspect", "--archive", str(archive), "--project", str(staged))
            self.assertEqual(code, 0, result)
            profile = result["projectProfile"]
            self.assertEqual(profile["framework"], "Android / Gradle")
            self.assertIn("Kotlin", profile["languages"])
            self.assertIn("AndroidManifest.xml", " ".join(profile["dependencyManifests"]) + " " + " ".join(result["listedFiles"]))
            self.assertIn("MainActivity.kt", " ".join(profile["entryPoints"]))
            self.assertIn(":app:testDebugUnitTest", profile["buildCommand"])
            self.assertIn(":app:assembleDebug", profile["buildCommand"])
            self.assertTrue(any("isolated" in warning for warning in profile["warnings"]))

    def test_react_vite_profile_lists_dependencies_and_offline_command(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            archive = root / "web.zip"
            staged = root / "staged"
            with zipfile.ZipFile(archive, "w") as out:
                out.writestr("web/package.json", json.dumps({"dependencies": {"react": "^18.0.0", "vite": "^5.0.0"}, "scripts": {"test": "vitest run"}}))
                out.writestr("web/package-lock.json", '{"name":"web","lockfileVersion":3,"packages":{}}')
                out.writestr("web/src/App.tsx", "export default function App() { return null }")
            code, result = invoke("inspect", "--archive", str(archive), "--project", str(staged))
            self.assertEqual(code, 0, result)
            profile = result["projectProfile"]
            self.assertEqual(profile["framework"], "React / Vite")
            self.assertIn("src/App.tsx", profile["entryPoints"])
            self.assertIn("npm", profile["buildCommand"])
            self.assertTrue(any(dep.startswith("react ") for dep in profile["dependencies"]))

    def test_unknown_source_project_does_not_invent_build_command(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / "notes.txt").write_text("An app idea", encoding="utf-8")
            # Call the helper in a fresh interpreter so test does not import/exercise project code.
            spec = "import importlib.util,json; s=importlib.util.spec_from_file_location('wr',%r); m=importlib.util.module_from_spec(s); s.loader.exec_module(m); print(json.dumps(m.describe_project(__import__('pathlib').Path(%r))))" % (str(SCRIPT), str(root))
            result = subprocess.run([sys.executable, "-c", spec], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            profile = json.loads(result.stdout)
            self.assertEqual(profile["framework"], "Unknown / source-only")
            self.assertEqual(profile["buildCommand"], "")


    def test_build_artifacts_are_copied_from_candidate_and_packaged_with_report(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            project = root / "project"
            artifacts = root / "artifacts"
            (project / "app/build/outputs/apk/debug").mkdir(parents=True)
            (project / "app/build/reports/tests/testDebugUnitTest").mkdir(parents=True)
            (project / "app/build/test-results/testDebugUnitTest").mkdir(parents=True)
            (project / "pyproject.toml").write_text("[project]\nname='fixture'\nversion='0.0.1'\n", encoding="utf-8")
            (project / "app/build/outputs/apk/debug/app-debug.apk").write_bytes(b"fixture-apk")
            (project / "app/build/reports/tests/testDebugUnitTest/index.html").write_text("<html>test report</html>", encoding="utf-8")
            (project / "app/build/test-results/testDebugUnitTest/TEST-fixture.xml").write_text("<testsuite tests='1' failures='0'/>", encoding="utf-8")
            code, result = invoke("test", "--project", str(project), "--timeout", "30", "--artifact-dir", str(artifacts))
            self.assertEqual(code, 0, result)
            self.assertEqual(result["status"], "SANDBOX_NOT_CONFIGURED")
            self.assertGreaterEqual(result["artifactCount"], 3)
            self.assertTrue((artifacts / "files/app/build/outputs/apk/debug/app-debug.apk").is_file())
            self.assertTrue((artifacts / "files/app/build/reports/tests/testDebugUnitTest/index.html").is_file())
            bundle = root / "artifacts.zip"
            code, packed = invoke("artifact-package", "--artifact-dir", str(artifacts), "--output", str(bundle))
            self.assertEqual(code, 0, packed)
            with zipfile.ZipFile(bundle) as archive:
                names = set(archive.namelist())
            self.assertIn("build-test-report.json", names)
            self.assertIn("files/app/build/outputs/apk/debug/app-debug.apk", names)
            self.assertIn("files/app/build/test-results/testDebugUnitTest/TEST-fixture.xml", names)

    def test_missing_sandbox_still_persists_a_redacted_build_report(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            project = root / "project"
            artifacts = root / "artifacts"
            project.mkdir()
            (project / "pyproject.toml").write_text("[project]\nname='fixture'\nversion='0.0.1'\n", encoding="utf-8")
            code, result = invoke("test", "--project", str(project), "--timeout", "30", "--artifact-dir", str(artifacts))
            self.assertEqual(code, 0, result)
            self.assertEqual(result["status"], "SANDBOX_NOT_CONFIGURED")
            report = artifacts / "build-test-report.json"
            self.assertTrue(report.is_file())
            payload = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(payload["status"], "SANDBOX_NOT_CONFIGURED")
            bundle = root / "artifacts.zip"
            code, zipped = invoke("artifact-package", "--artifact-dir", str(artifacts), "--output", str(bundle))
            self.assertEqual(code, 0, zipped)
            self.assertTrue(bundle.is_file())
            with zipfile.ZipFile(bundle) as out:
                self.assertIn("build-test-report.json", out.namelist())

class DisposableAndroidWorkflowTests(unittest.TestCase):
    def test_disposable_android_fixture_inspects_clones_and_fails_closed_without_worker(self):
        fixture = SCRIPT.parent.parent / "sandbox" / "fixtures" / "disposable-android"
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            archive = root / "fixture.zip"
            import shutil
            shutil.make_archive(str(root / "fixture"), "zip", fixture)
            code, inspected = invoke("inspect", "--archive", str(archive), "--project", str(root / "inspected"))
            self.assertEqual(code, 0, inspected)
            self.assertEqual(inspected["projectProfile"]["framework"], "Android / Gradle")
            code, cloned = invoke("clone", "--project", str(root / "inspected"), "--output", str(root / "candidate"))
            self.assertEqual(code, 0, cloned)
            code, report = invoke("test", "--project", str(root / "candidate"), "--timeout", "30", "--artifact-dir", str(root / "artifacts"))
            self.assertEqual(code, 0, report)
            self.assertEqual(report["status"], "SANDBOX_NOT_CONFIGURED")
            self.assertFalse((root / "candidate" / "app" / "build").exists())
            self.assertTrue((root / "artifacts" / "build-test-report.json").is_file())
