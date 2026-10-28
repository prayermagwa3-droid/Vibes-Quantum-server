#!/usr/bin/env python3
"""Portable, fail-honest whole-archive quality gate for Vibes Quantum.

The gate performs static checks and runs the repository's own Python unittest suite.
It does not build, install, or execute imported Android/Kotlin/Node/etc. projects.
Missing project build metadata is reported as unavailable, never as a pass.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

SCRIPT_DIR = Path(__file__).resolve().parent
SERVER_ROOT = SCRIPT_DIR.parent
DEFAULT_ROOT = SERVER_ROOT.parent

EXCLUDED_DIRS = {
    ".git", ".gradle", ".idea", ".venv", "venv", "node_modules", "build",
    "dist", "target", "__pycache__", ".pytest_cache", ".mypy_cache",
}
BUILD_MARKERS = {
    "gradlew", "settings.gradle", "settings.gradle.kts", "build.gradle",
    "build.gradle.kts", "pom.xml", "package.json", "pyproject.toml", "setup.py",
    "requirements.txt", "go.mod", "cargo.toml", "pubspec.yaml", "composer.json",
    "mix.exs", "build.sbt",
}
SOURCE_SUFFIXES = {
    ".kt", ".kts", ".java", ".py", ".js", ".mjs", ".cjs", ".ts", ".tsx",
    ".jsx", ".go", ".rs", ".dart", ".cs", ".swift", ".php", ".rb", ".scala",
}
SENSITIVE_FILENAMES = {
    ".env", "id_rsa", "id_ed25519", "credentials.json", "service-account.json",
    "secrets.json",
}
SECRET_PATTERNS = [
    ("private-key", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----")),
    ("aws-access-key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("github-token", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b")),
    ("bearer-token", re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/-]{24,}={0,2}")),
    ("credential-assignment", re.compile(
        r"(?i)\b(api[_-]?key|access[_-]?token|client[_-]?secret|password|secret)\b"
        r"\s*[:=]\s*[\"']([^\"'\s]{20,})[\"']"
    )),
]
PLACEHOLDER_FRAGMENTS = (
    "example", "placeholder", "your_", "your-", "changeme", "change-me",
    "replace-me", "replace_with", "replace-with", "dummy", "sample", "${",
    "<your", "insert_", "insert-", "abcdefghijklmnopqrstuvwxyz",
    "abcdefghijklmnop", "1234567890", "0123456789",
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _excluded(path: Path, root: Path) -> bool:
    try:
        parts = path.relative_to(root).parts
    except ValueError:
        return True
    return any(part in EXCLUDED_DIRS for part in parts)


def walk_files(root: Path) -> Iterable[Path]:
    """Walk source tree without descending into generated/dependency directories."""
    for current, dirs, files in os.walk(root):
        current_path = Path(current)
        dirs[:] = sorted(d for d in dirs if d not in EXCLUDED_DIRS)
        for name in sorted(files):
            path = current_path / name
            if not _excluded(path, root) and path.is_file() and not path.is_symlink():
                yield path


def has_source(directory: Path) -> bool:
    for path in walk_files(directory):
        if path.suffix.lower() in SOURCE_SUFFIXES:
            return True
    return False


def discover_projects(root: Path) -> list[dict[str, Any]]:
    """Identify the archive's known app/engine/server projects without counting fixtures."""
    root = root.resolve()
    known_names = ("Vibes_Quantum_Engine", "Vibes_Quantum_Server", "Vyron_User_App")
    projects: list[dict[str, Any]] = []
    for name in known_names:
        directory = root / name
        if directory.is_dir() and has_source(directory):
            files = list(walk_files(directory))
            markers = sorted({p.name for p in files if p.name.lower() in BUILD_MARKERS})
            root_markers = sorted(p.name for p in directory.iterdir() if p.is_file() and p.name.lower() in BUILD_MARKERS)
            projects.append({
                "name": name,
                "path": name,
                "source_file_count": sum(p.suffix.lower() in SOURCE_SUFFIXES for p in files),
                "build_markers_found": markers,
                "root_build_markers": root_markers,
                "build_status": "present" if root_markers else "unavailable",
            })

    if not projects:
        # Fallback for a single-project checkout or a differently named extracted root.
        files = list(walk_files(root))
        if any(p.suffix.lower() in SOURCE_SUFFIXES for p in files):
            markers = sorted({p.name for p in files if p.name.lower() in BUILD_MARKERS})
            root_markers = sorted(p.name for p in root.iterdir() if p.is_file() and p.name.lower() in BUILD_MARKERS)
            projects.append({
                "name": root.name or "source-root",
                "path": ".",
                "source_file_count": sum(p.suffix.lower() in SOURCE_SUFFIXES for p in files),
                "build_markers_found": markers,
                "root_build_markers": root_markers,
                "build_status": "present" if root_markers else "unavailable",
            })
    return projects


def inspect_python(root: Path) -> dict[str, Any]:
    checked = 0
    findings: list[dict[str, Any]] = []
    for path in walk_files(root):
        if path.suffix != ".py":
            continue
        try:
            source = path.read_text(encoding="utf-8")
            compile(source, str(path), "exec")
            checked += 1
        except (OSError, UnicodeError) as exc:
            findings.append({"path": path.relative_to(root).as_posix(), "issue": f"read-error:{type(exc).__name__}"})
        except SyntaxError as exc:
            findings.append({
                "path": path.relative_to(root).as_posix(),
                "issue": "syntax-error",
                "line": exc.lineno,
                "column": exc.offset,
                "message": (exc.msg or "invalid syntax")[:200],
            })
    return {"status": "passed" if not findings else "failed", "files_checked": checked, "findings": findings}


def inspect_json(root: Path) -> dict[str, Any]:
    checked = 0
    findings: list[dict[str, Any]] = []
    for path in walk_files(root):
        if path.suffix.lower() != ".json" or path.stat().st_size > 4_000_000:
            continue
        try:
            json.loads(path.read_text(encoding="utf-8"))
            checked += 1
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            findings.append({"path": path.relative_to(root).as_posix(), "issue": f"invalid-json:{type(exc).__name__}"})
    return {"status": "passed" if not findings else "failed", "files_checked": checked, "findings": findings}


def inspect_shell(root: Path) -> dict[str, Any]:
    bash = shutil.which("bash")
    scripts = [p for p in walk_files(root) if p.suffix == ".sh"]
    if not bash:
        return {"status": "unavailable", "reason": "bash is not installed", "files_checked": 0, "findings": []}
    checked = 0
    findings: list[dict[str, Any]] = []
    for path in scripts:
        result = subprocess.run([bash, "-n", str(path)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
        checked += 1
        if result.returncode:
            findings.append({"path": path.relative_to(root).as_posix(), "issue": "shell-syntax-error", "output": result.stdout[-1000:]})
    return {"status": "passed" if not findings else "failed", "files_checked": checked, "findings": findings}


def scan_secrets(root: Path) -> dict[str, Any]:
    findings: list[dict[str, Any]] = []
    skipped_large = 0
    for path in walk_files(root):
        rel = path.relative_to(root).as_posix()
        lower_name = path.name.lower()
        # Sample templates and examples are documentation, not active credentials.
        is_example = any(token in lower_name for token in (".example", "example.", ".sample", "template"))
        if lower_name in SENSITIVE_FILENAMES and not is_example:
            findings.append({"path": rel, "line": None, "kind": "sensitive-filename"})
        try:
            if path.stat().st_size > 2_000_000:
                skipped_large += 1
                continue
            content = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for line_number, line in enumerate(content.splitlines(), 1):
            for kind, pattern in SECRET_PATTERNS:
                match = pattern.search(line)
                if not match:
                    continue
                value = match.group(0).lower()
                if is_example or any(fragment in value for fragment in PLACEHOLDER_FRAGMENTS):
                    continue
                # Avoid reporting code that merely declares secret names without literals.
                if kind == "credential-assignment" and len(match.groups()) > 1:
                    candidate = match.group(2).lower()
                    if candidate.startswith(("${", "$env:", "env(", "os.getenv", "getenv(")):
                        continue
                findings.append({"path": rel, "line": line_number, "kind": kind})
                break
    # Findings intentionally contain locations and detector names only, never matched values.
    return {"status": "passed" if not findings else "failed", "findings": findings, "large_files_skipped": skipped_large}


def run_unit_tests(root: Path, timeout_seconds: int = 180) -> dict[str, Any]:
    scripts = root / "Vibes_Quantum_Server" / "scripts"
    if not scripts.is_dir():
        scripts = root / "scripts"
    tests = sorted(scripts.glob("test_*.py")) if scripts.is_dir() else []
    if not tests:
        return {"status": "unavailable", "reason": "No repository test_*.py suite was found", "tests_discovered": 0}
    start = time.perf_counter()
    try:
        proc = subprocess.run(
            [sys.executable, "-m", "unittest", "discover", "-s", str(scripts), "-p", "test_*.py", "-v"],
            cwd=scripts,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout_seconds,
            check=False,
        )
        output = proc.stdout or ""
        count_match = re.search(r"Ran\s+(\d+)\s+tests?\s+in", output)
        return {
            "status": "passed" if proc.returncode == 0 else "failed",
            "exit_code": proc.returncode,
            "tests_discovered": len(tests),
            "tests_run": int(count_match.group(1)) if count_match else None,
            "duration_seconds": round(time.perf_counter() - start, 3),
            "output_tail": output[-6000:],
        }
    except subprocess.TimeoutExpired as exc:
        output = exc.stdout or ""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        return {"status": "failed", "reason": "timeout", "timeout_seconds": timeout_seconds, "output_tail": output[-2000:]}
    except OSError as exc:
        return {"status": "unavailable", "reason": f"test-runner-error:{type(exc).__name__}"}


def compose_status(checks: dict[str, Any], projects: list[dict[str, Any]]) -> str:
    """Return a fail-honest status; unavailable checks can never count as passed."""
    critical = ("python_syntax", "json_syntax", "shell_syntax", "secret_scan", "python_unit_tests")
    if any(checks.get(key, {}).get("status") == "failed" for key in critical):
        return "BLOCKED"
    if any(checks.get(key, {}).get("status") != "passed" for key in critical):
        return "NOT_READY"
    if not projects or any(p.get("build_status") != "present" for p in projects):
        return "NOT_READY"
    return "READY_FOR_REVIEW"


def build_report(root: Path, run_tests: bool = True, timeout_seconds: int = 180) -> dict[str, Any]:
    root = root.resolve()
    projects = discover_projects(root)
    checks: dict[str, Any] = {
        "python_syntax": inspect_python(root),
        "json_syntax": inspect_json(root),
        "shell_syntax": inspect_shell(root),
        "secret_scan": scan_secrets(root),
    }
    if run_tests:
        checks["python_unit_tests"] = run_unit_tests(root, timeout_seconds)
    else:
        checks["python_unit_tests"] = {"status": "skipped", "reason": "Disabled by command-line option"}
    missing_builds = [p["path"] for p in projects if p["build_status"] != "present"]
    checks["build_readiness"] = {
        "status": "passed" if projects and not missing_builds else "unavailable",
        "reason": None if projects and not missing_builds else "One or more source projects lack root build metadata; no compile/build result is claimed",
        "projects_missing_root_build_metadata": missing_builds,
    }
    return {
        "schema_version": 1,
        "created_utc": utc_now(),
        "root_name": root.name,
        "scope": "source inventory, static syntax, credential-pattern scan, included Python tests, build metadata presence",
        "projects": projects,
        "checks": checks,
        "overall": compose_status(checks, projects),
        "limitations": [
            "No Android/Kotlin/Ktor compile or APK build is run by this gate.",
            "A source file and a passing static check do not prove runtime integration.",
            "Secret-pattern scanning is best-effort and cannot guarantee that every credential format is detected.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT, help="Extracted archive/project root")
    parser.add_argument("--output", type=Path, help="Optional JSON report destination (default: print only)")
    parser.add_argument("--skip-unit-tests", action="store_true", help="Run static checks only")
    parser.add_argument("--test-timeout", type=int, default=180, help="Python test suite timeout in seconds")
    args = parser.parse_args()
    if args.test_timeout < 1 or args.test_timeout > 3600:
        parser.error("--test-timeout must be between 1 and 3600 seconds")
    if not args.root.is_dir():
        parser.error(f"--root is not a directory: {args.root}")
    report = build_report(args.root, run_tests=not args.skip_unit_tests, timeout_seconds=args.test_timeout)
    rendered = json.dumps(report, indent=2, sort_keys=True)
    if args.output:
        destination = args.output.resolve()
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0 if report["overall"] == "READY_FOR_REVIEW" else (1 if report["overall"] == "BLOCKED" else 2)


if __name__ == "__main__":
    raise SystemExit(main())
