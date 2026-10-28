#!/usr/bin/env python3
"""Guarded project ZIP staging and sandboxed test runner for Vibes Quantum Engine.

This tool never executes project code on the host. Archive reading, path validation,
source inspection, and git-apply are non-executing host operations. Project tests run
only in a preloaded Docker/Podman image, with networking disabled and a private
staging directory as the only writable host mount.
"""
from __future__ import annotations

import argparse
import ast
import difflib
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import time
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_EXPANDED_BYTES = 250 * 1024 * 1024
MAX_ENTRIES = 5000
MAX_FILES = 1800
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_PATCH_BYTES = 18000
MAX_PATCH_PATHS = 8
MAX_OUTPUT = 12000
SKIP_DIRS = {".git", ".gradle", ".idea", ".vscode", "build", "node_modules", "dist", "target", "__pycache__", ".pytest_cache", ".venv", "venv", "Pods", "DerivedData", "vendor"}
SECRET_NAMES = {".env", ".env.local", ".env.production", "local.properties", "credentials.json", "service-account.json", "google-services.json", "id_rsa", "id_ed25519"}
TEXT_EXTENSIONS = {".kt", ".java", ".kts", ".gradle", ".xml", ".properties", ".toml", ".json", ".yml", ".yaml", ".md", ".txt", ".py", ".js", ".jsx", ".ts", ".tsx", ".html", ".css", ".sql", ".sh", ".pro", ".cmake", ".c", ".h", ".cpp", ".dart", ".swift", ".rs", ".go", ".cs", ".php", ".rb", ".vue", ".svelte", ".proto", ".gradle.kts"}
PRIVATE_KEY_RE = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----.*?-----END (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----", re.I | re.S)
TOKEN_RE = re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{25,}|vq_live_[A-Za-z0-9_-]{25,})\b", re.I)
ASSIGNMENT_RE = re.compile(r"(?i)(api[_-]?key|access[_-]?token|client[_-]?secret|password|authorization|secret[_-]?key)(\s*[:=]\s*)['\"]?[^\s,; '\"]{8,}")


def emit(payload: dict[str, Any], code: int = 0) -> None:
    print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    raise SystemExit(code)


def safe_path(raw: str) -> str | None:
    value = raw.replace("\\", "/")
    if not value or len(value) > 500 or any(ord(ch) < 32 for ch in value) or value.startswith("/") or re.match(r"^[A-Za-z]:", value):
        return None
    parts = value.split("/")
    if any(part in ("..", "") for part in parts):
        return None
    clean = [part for part in parts if part != "."]
    return "/".join(clean) if clean else None


def should_skip(path: str) -> bool:
    parts = path.split("/")
    name = parts[-1].lower()
    if any(part in SKIP_DIRS for part in parts):
        return True
    if name in SECRET_NAMES or name.endswith((".jks", ".keystore", ".p12", ".pfx", ".pem", ".key")):
        return True
    if name.endswith((".apk", ".aab", ".dex", ".so", ".class", ".jar", ".war", ".zip", ".tar", ".gz")):
        return True
    return False


def redact(text: str) -> str:
    text = PRIVATE_KEY_RE.sub("[PRIVATE KEY REDACTED]", text)
    text = TOKEN_RE.sub("[TOKEN REDACTED]", text)
    return ASSIGNMENT_RE.sub(lambda m: f"{m.group(1)}{m.group(2)}[REDACTED]", text)


def extract_archive(archive: Path, project_dir: Path) -> dict[str, Any]:
    if not archive.is_file():
        raise ValueError("Project archive was not found.")
    if archive.stat().st_size <= 0 or archive.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ValueError("ZIP must be between 1 byte and 64 MiB.")
    project_dir.mkdir(parents=True, exist_ok=False)
    files: list[str] = []
    warnings: list[str] = []
    seen = expanded = 0
    try:
        with zipfile.ZipFile(archive) as zf:
            entries = zf.infolist()
            if not entries:
                raise ValueError("The ZIP archive is empty.")
            if len(entries) > MAX_ENTRIES:
                raise ValueError(f"ZIP contains more than {MAX_ENTRIES} entries.")
            for info in entries:
                if info.is_dir():
                    continue
                seen += 1
                name = safe_path(info.filename)
                if name is None:
                    warnings.append("Unsafe archive path skipped; no path traversal was extracted.")
                    continue
                mode = (info.external_attr >> 16) & 0xFFFF
                if stat.S_ISLNK(mode):
                    warnings.append("Symbolic link skipped from archive.")
                    continue
                expanded += max(info.file_size, 0)
                if expanded > MAX_EXPANDED_BYTES:
                    raise ValueError("ZIP expands beyond the 250 MiB inspection/extraction limit.")
                if info.file_size > MAX_FILE_BYTES:
                    warnings.append(f"Large file skipped: {name}")
                    continue
                if should_skip(name):
                    if Path(name).name.lower() in SECRET_NAMES or Path(name).suffix.lower() in {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"}:
                        warnings.append(f"Potential secret/credential file excluded: {name}")
                    continue
                if len(files) >= MAX_FILES:
                    warnings.append(f"File limit reached ({MAX_FILES}); remaining entries omitted.")
                    break
                dest = (project_dir / name).resolve()
                if not dest.is_relative_to(project_dir.resolve()):
                    warnings.append("Path outside project root skipped.")
                    continue
                dest.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info) as source, dest.open("wb") as target:
                    copied = 0
                    while True:
                        chunk = source.read(65536)
                        if not chunk:
                            break
                        copied += len(chunk)
                        if copied > MAX_FILE_BYTES:
                            raise ValueError(f"File expanded beyond its per-file limit: {name}")
                        target.write(chunk)
                files.append(name)
        if not files:
            raise ValueError("No safe project files remained after archive validation.")
        # Normalize project root if archive wraps all content in one folder.
        root = project_dir
        current = root
        for _ in range(3):
            children = list(current.iterdir())
            has_project_marker = any((current / m).is_file() for m in ("settings.gradle", "settings.gradle.kts", "pom.xml", "package.json", "pyproject.toml", "Cargo.toml", "go.mod", "build.gradle", "build.gradle.kts"))
            if has_project_marker or len(children) != 1 or not children[0].is_dir():
                break
            current = children[0]
        rel_root = str(current.relative_to(project_dir)) if current != project_dir else "."
        rel_files = sorted(files)
        root_prefix = "" if rel_root == "." else rel_root.rstrip("/") + "/"
        root_files = [f[len(root_prefix):] for f in rel_files if f.startswith(root_prefix)] if root_prefix else rel_files
        # The archive can contain multiple projects; keep all safe files, but give the best root hint.
        candidate_files = []
        for item in root_files:
            full = current / item
            if full.is_file() and full.suffix.lower() in TEXT_EXTENSIONS and full.stat().st_size <= 96 * 1024:
                try:
                    body = full.read_text(encoding="utf-8")
                except (UnicodeDecodeError, OSError):
                    continue
                if "\x00" in body:
                    continue
                candidate_files.append({"path": item, "excerpt": redact(body[:1600])})
        markers = {
            "gradle": any(Path(f).name in {"settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"} for f in root_files),
            "android": any(Path(f).name == "AndroidManifest.xml" for f in root_files),
            "python": any(Path(f).name in {"pyproject.toml", "pytest.ini", "setup.cfg", "requirements.txt"} for f in root_files),
            "node": any(Path(f).name == "package.json" for f in root_files),
            "maven": any(Path(f).name == "pom.xml" for f in root_files),
            "rust": any(Path(f).name == "Cargo.toml" for f in root_files),
            "go": any(Path(f).name == "go.mod" for f in root_files),
        }
        profile = describe_project(current)
        return {"ok": True, "fileCount": len(files), "listedFiles": root_files[:1200], "allFiles": rel_files[:1200], "projectRoot": rel_root, "markers": markers, "projectProfile": profile, "excerpts": candidate_files[:30], "warnings": sorted(set(warnings)) + profile.get("warnings", [])}
    except Exception:
        shutil.rmtree(project_dir, ignore_errors=True)
        raise



def index_project(project: Path) -> dict[str, Any]:
    project = project.resolve()
    if not project.is_dir():
        raise ValueError("Staged project directory was not found.")
    files: list[str] = []
    excerpts: list[dict[str, str]] = []
    warnings: list[str] = []
    total = 0
    for path in sorted(project.rglob("*")):
        if path.is_symlink():
            continue
        rel = path.relative_to(project).as_posix()
        if should_skip(rel) or rel == ".workspace-container":
            continue
        if not path.is_file():
            continue
        if len(files) >= MAX_FILES:
            warnings.append(f"Index limit reached ({MAX_FILES} files).")
            break
        files.append(rel)
        total += path.stat().st_size
        if total > MAX_EXPANDED_BYTES:
            warnings.append("Index byte limit reached; later files are listed but not excerpted.")
            break
        if len(excerpts) >= 30 or path.stat().st_size > 96 * 1024 or path.suffix.lower() not in TEXT_EXTENSIONS:
            continue
        try:
            body = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if "\x00" in body:
            continue
        excerpts.append({"path": rel, "excerpt": redact(body[:1600])})
    names = {Path(f).name for f in files}
    markers = {
        "gradle": bool(names & {"settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"}),
        "android": "AndroidManifest.xml" in names,
        "python": bool(names & {"pyproject.toml", "pytest.ini", "setup.cfg", "requirements.txt"}),
        "node": "package.json" in names,
        "maven": "pom.xml" in names,
        "rust": "Cargo.toml" in names,
        "go": "go.mod" in names,
    }
    return {"ok": True, "fileCount": len(files), "listedFiles": files[:1200], "markers": markers, "projectProfile": describe_project(project), "excerpts": excerpts, "warnings": warnings}



def clone_project(source: Path, output: Path) -> dict[str, Any]:
    """Create a bounded, symlink-free copy while excluding build caches and credentials."""
    source = source.resolve()
    if not source.is_dir():
        raise ValueError("Source project directory was not found.")
    if output.exists() or output.is_symlink():
        raise ValueError("Clone destination must not already exist.")
    output_parent = output.parent.resolve()
    if not output_parent.is_dir():
        raise ValueError("Clone destination parent was not found.")
    copied = 0
    total = 0
    output.mkdir(parents=False, exist_ok=False)
    try:
        for base, dirs, files in os.walk(source, topdown=True, followlinks=False):
            base_path = Path(base)
            dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS and not (base_path / d).is_symlink())
            for name in sorted(files):
                original = base_path / name
                if original.is_symlink():
                    continue
                rel = original.relative_to(source).as_posix()
                if should_skip(rel) or rel == ".workspace-container":
                    continue
                size = original.stat().st_size
                total += size
                if total > MAX_EXPANDED_BYTES:
                    raise ValueError("Staged project exceeds the 250 MiB clone limit.")
                if size > MAX_FILE_BYTES:
                    continue
                if copied >= MAX_FILES:
                    raise ValueError(f"Staged project exceeds the {MAX_FILES}-file clone limit.")
                target = output / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(original, target, follow_symlinks=False)
                copied += 1
        if copied == 0:
            raise ValueError("No safe files could be copied into the isolated workspace.")
        return {"ok": True, "status": "PROJECT_CLONED", "filesCopied": copied, "bytesCopied": total, "message": "Symlink-free project copy created; source remained unchanged."}
    except Exception:
        shutil.rmtree(output, ignore_errors=True)
        raise

def patch_paths(patch: str) -> list[str]:
    paths: list[str] = []
    for line in patch.splitlines():
        if line.startswith("--- ") or line.startswith("+++ "):
            raw = line[4:].split("\t", 1)[0].strip()
            if raw == "/dev/null":
                continue
            raw = raw.removeprefix("a/").removeprefix("b/")
            normalized = safe_path(raw)
            if normalized is None:
                raise ValueError("Patch contains an absolute or parent-traversal path.")
            if normalized not in paths:
                paths.append(normalized)
    return paths


def apply_patch(project: Path, patch_file: Path) -> dict[str, Any]:
    if not patch_file.is_file() or patch_file.stat().st_size == 0 or patch_file.stat().st_size > MAX_PATCH_BYTES:
        raise ValueError("Patch must be between 1 byte and 18,000 bytes.")
    patch = patch_file.read_text(encoding="utf-8")
    if "\x00" in patch or "GIT binary patch" in patch or "Submodule " in patch:
        raise ValueError("Binary, NUL-containing, and submodule patches are not accepted.")
    paths = patch_paths(patch)
    if not paths or len(paths) > MAX_PATCH_PATHS:
        raise ValueError(f"Patch must touch between 1 and {MAX_PATCH_PATHS} files.")
    lines = patch.splitlines()
    changed = sum(1 for line in lines if line.startswith(("+", "-")) and not line.startswith(("+++", "---")))
    if changed > 300:
        raise ValueError("Patch changes more than 300 lines; split it into smaller reviewable changes.")
    if any(any(part in SKIP_DIRS for part in path.split("/")) for path in paths):
        raise ValueError("Patch targets a generated/cache directory.")
    project_root = project.resolve()
    for path in paths:
        lexical_target = project_root / path
        # Check every lexical component before resolving; checking is_symlink() after
        # resolve() misses links because resolution has already followed them.
        cursor = project_root
        for part in path.split("/"):
            cursor = cursor / part
            if cursor.is_symlink():
                raise ValueError("Patch refuses to modify or traverse symbolic links.")
        target = lexical_target.resolve()
        if not target.is_relative_to(project_root):
            raise ValueError("Patch path escapes the project root.")
    git = shutil.which("git")
    if git is None:
        raise RuntimeError("git is not available; patch validation failed closed.")
    base = [git, "apply", "--whitespace=nowarn"]
    check = subprocess.run(base + ["--check", str(patch_file)], cwd=project, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10, check=False)
    if check.returncode != 0:
        return {"ok": False, "status": "PATCH_REJECTED", "message": redact(check.stdout[-4000:]) or "Patch did not apply cleanly.", "paths": paths}
    result = subprocess.run(base + [str(patch_file)], cwd=project, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10, check=False)
    if result.returncode != 0:
        return {"ok": False, "status": "PATCH_REJECTED", "message": redact(result.stdout[-4000:]) or "Patch could not be applied.", "paths": paths}
    return {"ok": True, "status": "PATCH_APPLIED_TO_STAGING_ONLY", "message": "Patch applied to the isolated job copy. Original ZIP and live app files were not changed.", "paths": paths, "changedLines": changed}


def _project_files(project: Path) -> list[Path]:
    return [p for p in project.rglob("*") if p.is_file() and not p.is_symlink() and not should_skip(p.relative_to(project).as_posix())][:MAX_FILES]


def _safe_manifest_excerpt(path: Path, limit: int = 96 * 1024) -> str:
    try:
        if path.is_file() and path.stat().st_size <= limit:
            return redact(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError):
        pass
    return ""


def detect_command(project: Path) -> tuple[list[str] | None, str, str]:
    files = _project_files(project)
    names = {p.name for p in files}
    paths = {p.relative_to(project).as_posix() for p in files}
    android = any(Path(path).name == "AndroidManifest.xml" for path in paths)
    gradle = bool(names & {"settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"})
    if gradle:
        gradle_bin = ["gradle"]  # Never run an untrusted wrapper that may download code.
        if android:
            app_marker = any(path in {"app/build.gradle", "app/build.gradle.kts"} for path in paths)
            if app_marker:
                return gradle_bin + ["--offline", "--no-daemon", ":app:testDebugUnitTest", ":app:assembleDebug"], "gradle-android", "Android unit tests and debug APK assembly (offline; requires preloaded dependencies)"
            return gradle_bin + ["--offline", "--no-daemon", "test", "assembleDebug"], "gradle-android", "Gradle Android test/build (generic modules; offline dependencies required)"
        return gradle_bin + ["--offline", "--no-daemon", "test"], "gradle", "Gradle tests (offline)"
    if "pom.xml" in names:
        return ["mvn", "-o", "-q", "test"], "maven", "Maven tests (offline)"
    if "pyproject.toml" in names or "pytest.ini" in names or any(p.name.startswith("test_") and p.suffix == ".py" for p in files):
        return ["python", "-I", "-m", "pytest", "-q"], "python", "Pytest suite"
    if "package.json" in names:
        if not ({"package-lock.json", "npm-shrinkwrap.json"} & names):
            return None, "node", "A package-lock.json or npm-shrinkwrap.json is required for a deterministic offline install"
        return ["sh", "-lc", "npm ci --offline --ignore-scripts && npm test"], "node", "Offline npm install from lockfile, then npm test"
    if "Cargo.toml" in names:
        return ["cargo", "test", "--offline", "--locked"], "rust", "Rust test suite (offline)"
    if "go.mod" in names:
        return ["go", "test", "./..."], "go", "Go tests (network disabled)"
    if "pubspec.yaml" in names:
        return ["flutter", "test", "--no-pub"], "flutter", "Flutter tests using preloaded packages"
    if any(path.endswith(".csproj") or path.endswith(".sln") for path in paths):
        return ["dotnet", "test", "--no-restore"], "dotnet", ".NET tests using pre-restored packages"
    return None, "unknown", "No supported deterministic offline build/test manifest found"


def describe_project(project: Path) -> dict[str, Any]:
    """Detect likely framework, languages, dependency manifests, entry points and safe command suggestions."""
    files = _project_files(project)
    paths = [p.relative_to(project).as_posix() for p in files]
    names = {Path(item).name for item in paths}
    suffixes = {Path(item).suffix.lower() for item in paths}
    manifests = sorted(name for name in names if name in {
        "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "gradle.properties",
        "pom.xml", "package.json", "package-lock.json", "npm-shrinkwrap.json", "pyproject.toml",
        "requirements.txt", "Pipfile", "Cargo.toml", "Cargo.lock", "go.mod", "go.sum",
        "pubspec.yaml", "Podfile", "Package.swift", "*.csproj", "*.sln", "composer.json", "Gemfile",
        "requirements-dev.txt", "poetry.lock", "pnpm-lock.yaml", "yarn.lock"
    } or name.endswith((".csproj", ".sln")))
    android = any(Path(item).name == "AndroidManifest.xml" for item in paths)
    flutter = "pubspec.yaml" in names and any(Path(item).suffix.lower() == ".dart" for item in paths)
    package = {}
    for path in files:
        if path.name == "package.json":
            try:
                import json as _json
                package = _json.loads(_safe_manifest_excerpt(path)[:96 * 1024])
            except Exception:
                package = {}
            break
    if android and bool(names & {"settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"}):
        framework = "Android / Gradle"
    elif flutter:
        framework = "Flutter / Dart"
    elif bool(names & {"settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"}) and suffixes & {".kt", ".kts"}:
        framework = "Kotlin / Gradle"
    elif "pom.xml" in names:
        framework = "Java / Maven"
    elif "Cargo.toml" in names:
        framework = "Rust / Cargo"
    elif "go.mod" in names:
        framework = "Go modules"
    elif "package.json" in names:
        deps = {**(package.get("dependencies") or {}), **(package.get("devDependencies") or {})}
        if "next" in deps:
            framework = "Next.js / Node.js"
        elif "@angular/core" in deps or "@angular/cli" in deps:
            framework = "Angular / Node.js"
        elif "vite" in deps and "react" in deps:
            framework = "React / Vite"
        elif "vite" in deps and "vue" in deps:
            framework = "Vue / Vite"
        elif "svelte" in deps or "@sveltejs/kit" in deps:
            framework = "Svelte / Node.js"
        else:
            framework = "JavaScript / Node.js"
    elif bool(names & {"pyproject.toml", "requirements.txt", "pytest.ini", "setup.cfg"}):
        pydeps = " ".join(_safe_manifest_excerpt(p, 32 * 1024).lower() for p in files if p.name in {"pyproject.toml", "requirements.txt"})
        framework = "Python / FastAPI" if "fastapi" in pydeps else "Python / Django" if "django" in pydeps else "Python / Flask" if "flask" in pydeps else "Python"
    elif any(item.endswith((".csproj", ".sln")) for item in paths):
        framework = ".NET"
    elif "Package.swift" in names or "Podfile" in names:
        framework = "Apple / Swift"
    elif "composer.json" in names:
        framework = "PHP / Composer"
    elif "Gemfile" in names:
        framework = "Ruby / Bundler"
    elif any(path.endswith((".html", ".css", ".js")) for path in paths):
        framework = "Static web / JavaScript"
    else:
        framework = "Unknown / source-only"

    languages = []
    for suffix, label in [(".kt", "Kotlin"), (".java", "Java"), (".kts", "Kotlin DSL"), (".py", "Python"), (".js", "JavaScript"), (".jsx", "React JSX"), (".ts", "TypeScript"), (".tsx", "TypeScript JSX"), (".dart", "Dart"), (".rs", "Rust"), (".go", "Go"), (".swift", "Swift"), (".cs", "C#"), (".php", "PHP"), (".rb", "Ruby"), (".html", "HTML"), (".css", "CSS")]:
        if suffix in suffixes:
            languages.append(label)
    entry_patterns = (
        "app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml", "app/src/main/java/", "app/src/main/kotlin/",
        "src/main.kt", "src/main/kotlin/", "src/main/java/", "Main.kt", "main.py", "app.py", "manage.py",
        "src/main.py", "src/index.tsx", "src/main.tsx", "src/index.jsx", "src/main.jsx", "src/main.ts",
        "src/App.tsx", "src/App.jsx", "src/main.js", "src/main.tsx", "src/main.jsx", "src/app/page.tsx",
        "app/page.tsx", "pages/index.tsx", "src/pages/index.tsx", "src/app/app.component.ts", "src/main.ts",
        "index.html", "src/main.dart", "lib/main.dart", "cmd/", "main.go", "src/main.rs", "Package.swift"
    )
    entries = [path for path in paths if any(path == pat or path.startswith(pat) for pat in entry_patterns)][:30]
    command, ecosystem, description = detect_command(project)
    warnings = []
    if ecosystem == "unknown":
        warnings.append("Framework/build command not fully detected; review entry points and add an explicit reviewed build adapter.")
    if ecosystem in {"gradle-android", "gradle", "maven", "node", "python", "rust", "go", "flutter", "dotnet"}:
        warnings.append("Commands are proposals only. They execute only in the configured isolated, network-disabled worker; required dependencies must already be cached.")
    deps: list[str] = []
    for path in files:
        if path.name == "package.json":
            try:
                manifest = __import__("json").loads(_safe_manifest_excerpt(path))
                for section in ("dependencies", "devDependencies", "peerDependencies"):
                    for dep, version in (manifest.get(section) or {}).items():
                        deps.append(f"{dep} {version}")
            except Exception:
                pass
        elif path.name in {"requirements.txt", "requirements-dev.txt"}:
            for line in _safe_manifest_excerpt(path).splitlines():
                line = line.strip()
                if line and not line.startswith(("#", "-")) and re.match(r"^[A-Za-z0-9_.-]+", line):
                    deps.append(line[:180])
        elif path.name == "pyproject.toml":
            in_dependencies = False
            for line in _safe_manifest_excerpt(path).splitlines():
                if re.match(r"^\s*\[.*dependencies.*\]", line, re.I):
                    in_dependencies = True
                    continue
                if re.match(r"^\s*\[", line):
                    in_dependencies = False
                if in_dependencies and re.match(r"^\s*['\"]?[A-Za-z0-9_.-]+", line):
                    deps.append(line.strip().rstrip(",")[:180])
        elif path.name == "pom.xml":
            body = _safe_manifest_excerpt(path)
            deps.extend(f"Maven dependency {group}:{artifact}:{version}" for group, artifact, version in re.findall(
                r"<groupId>\s*([^<]+)\s*</groupId>\s*<artifactId>\s*([^<]+)\s*</artifactId>\s*<version>\s*([^<]+)", body
            )[:40])
        elif path.name in {"build.gradle", "build.gradle.kts"}:
            body = _safe_manifest_excerpt(path)
            deps.extend(re.findall(r"(?m)^\s*(?:implementation|api|testImplementation|testImplementation|classpath)\s*\(?\s*['\"]([^'\"]+)['\"]", body)[:40])
    return {
        "framework": framework,
        "languages": languages[:12],
        "dependencyManifests": manifests[:30],
        "dependencies": sorted(set(deps))[:80],
        "entryPoints": entries,
        "buildCommand": " ".join(command) if command else "",
        "buildDescription": description,
        "ecosystem": ecosystem,
        "warnings": warnings,
    }


def _artifact_is_sensitive(path: Path) -> bool:
    """Conservatively scan the whole candidate artifact using bounded-memory chunks."""
    key_markers = (
        re.compile(rb"(?i)\b(?:gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{25,}|vq_live_[A-Za-z0-9_-]{25,})\b"),
        re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----", re.I),
        re.compile(rb"(?i)(api[_-]?key|access[_-]?token|client[_-]?secret|password)[=: ]{1,8}[A-Za-z0-9_./+=-]{12,}"),
    )
    try:
        carry = b""
        with path.open("rb") as stream:
            while True:
                chunk = stream.read(64 * 1024)
                if not chunk:
                    return False
                buffer = carry + chunk
                if any(pattern.search(buffer) for pattern in key_markers):
                    return True
                carry = buffer[-2048:]
    except OSError:
        return True


def _collect_build_outputs(project: Path, artifact_dir: Path) -> list[dict[str, Any]]:
    """Copy a strict allowlist of generated artifacts/reports outside the disposable project copy."""
    copied: list[dict[str, Any]] = []
    root = project.resolve()
    target_root = artifact_dir.resolve()
    (target_root / "files").mkdir(parents=True, exist_ok=True)
    total_bytes = 0
    for source in sorted(root.rglob("*")):
        if source.is_symlink() or not source.is_file():
            continue
        try:
            rel = source.relative_to(root).as_posix()
            size = source.stat().st_size
        except (OSError, ValueError):
            continue
        lower = rel.lower()
        ext = source.suffix.lower()
        is_binary_artifact = ext in {".apk", ".aab"} and ("/outputs/apk/" in f"/{lower}" or "/outputs/bundle/" in f"/{lower}" or "/outputs/" in f"/{lower}")
        is_junit_report = ext == ".xml" and ("/test-results/" in f"/{lower}" or "/surefire-reports/" in f"/{lower}" or "/failsafe-reports/" in f"/{lower}")
        is_html_report = source.name == "index.html" and "/reports/tests/" in f"/{lower}"
        if not (is_binary_artifact or is_junit_report or is_html_report):
            continue
        if size <= 0 or size > 450 * 1024 * 1024 or total_bytes + size > 600 * 1024 * 1024:
            copied.append({"path": rel, "kind": "skipped", "bytes": max(0, size), "reason": "Artifact exceeded size policy."})
            continue
        safe_rel = safe_path(rel)
        if safe_rel is None or any(part in {"..", ".workspace-container"} for part in safe_rel.split("/")):
            continue
        if _artifact_is_sensitive(source):
            copied.append({"path": rel, "kind": "skipped", "bytes": size, "reason": "Possible credential marker detected; artifact withheld."})
            continue
        dest = target_root / "files" / safe_rel
        if not dest.resolve().is_relative_to((target_root / "files").resolve()):
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest, follow_symlinks=False)
        total_bytes += size
        copied.append({"path": f"files/{safe_rel}", "sourcePath": rel, "kind": "apk" if ext == ".apk" else "aab" if ext == ".aab" else "test-report", "bytes": size})
        if len(copied) >= 30:
            break
    return copied


def _persist_test_report(project: Path, artifact_dir: Path | None, result: dict[str, Any]) -> dict[str, Any]:
    if artifact_dir is None:
        return result
    try:
        target = artifact_dir.resolve()
        target.mkdir(parents=True, exist_ok=True)
        result = dict(result)
        result["projectProfile"] = describe_project(project)
        result["artifacts"] = _collect_build_outputs(project, target) if project.is_dir() else []
        result["artifactCount"] = sum(1 for item in result.get("artifacts", []) if item.get("kind") in {"apk", "aab", "test-report"})
        report_path = target / "build-test-report.json"
        report = redact(json.dumps(result, ensure_ascii=False, indent=2)[:MAX_OUTPUT * 2])
        report_path.write_text(report, encoding="utf-8")
    except Exception as error:
        result = dict(result)
        result["artifactPersistenceWarning"] = f"Build/test report or artifacts could not be persisted: {type(error).__name__}."
    return result


def test_project(project: Path, timeout: int, artifact_dir: Path | None = None) -> dict[str, Any]:
    command, ecosystem, description = detect_command(project)

    def complete(result: dict[str, Any]) -> dict[str, Any]:
        return _persist_test_report(project, artifact_dir, result)

    if command is None:
        return complete({"ok": False, "status": "TESTS_UNAVAILABLE", "ecosystem": ecosystem, "description": description, "exitCode": None, "durationSeconds": 0, "output": "Add a supported build/test manifest or configure a build adapter. No project code was executed."})
    image = os.environ.get("VIBES_WORKSPACE_SANDBOX_IMAGE", "").strip()
    runtime = os.environ.get("VIBES_WORKSPACE_SANDBOX_RUNTIME", "docker").strip()
    if not image or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/:@-]{0,255}", image) or image.startswith("-"):
        return complete({"ok": False, "status": "SANDBOX_NOT_CONFIGURED", "ecosystem": ecosystem, "description": description, "exitCode": None, "durationSeconds": 0, "output": "VIBES_WORKSPACE_SANDBOX_IMAGE is not configured. Build/test execution is disabled; candidate code remains staged and unexecuted."})
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", runtime):
        return complete({"ok": False, "status": "SANDBOX_INVALID_CONFIG", "ecosystem": ecosystem, "description": description, "exitCode": None, "durationSeconds": 0, "output": "Sandbox runtime name is invalid. No project code was executed."})
    if shutil.which(runtime) is None:
        return complete({"ok": False, "status": "SANDBOX_RUNTIME_MISSING", "ecosystem": ecosystem, "description": description, "exitCode": None, "durationSeconds": 0, "output": f"Configured {runtime} runtime is not installed. No project code was executed."})
    name = "vq-workspace-" + re.sub(r"[^a-z0-9]", "", os.urandom(10).hex())
    marker = project.parent / ".workspace-container"
    marker.write_text(name, encoding="ascii")
    started = time.monotonic()
    project.chmod(0o755)
    for entry in project.rglob("*"):
        if entry.is_symlink():
            continue
        if entry.is_dir():
            try: entry.chmod(0o777)
            except OSError: pass
        elif entry.is_file():
            try: entry.chmod(0o666 | (0o111 if entry.name in {"gradlew", "mvnw"} else 0))
            except OSError: pass
    seed_script = (
        'set -eu; '
        'mkdir -p "$HOME/.gradle" "$HOME/.npm" "$HOME/.m2/repository" "$HOME/.cargo" "$HOME/go/pkg/mod" "$GOCACHE"; '
        'for item in "gradle:$HOME/.gradle" "npm:$HOME/.npm" "maven-repository:$HOME/.m2/repository" "cargo:$HOME/.cargo" "go-mod:$GOMODCACHE"; do '
        'name=${item%%:*}; dst=${item#*:}; src="/opt/vibes/cache/$name"; '
        'if [ -d "$src" ]; then cp -a "$src"/. "$dst"/; fi; '
        'done; '
    )
    command_line = seed_script + " ".join(shell_quote(x) for x in command)
    args = [
        runtime, "run", "--rm", "--pull=never", "--name", name,
        "--network", "none", "--read-only", "--memory=2048m", "--cpus=2", "--pids-limit=256",
        "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user=65534:65534",
        "--tmpfs", "/tmp:rw,nosuid,size=512m", "--tmpfs", "/home/runner:rw,nosuid,size=256m",
        "--env", "HOME=/home/runner", "--env", "GRADLE_USER_HOME=/home/runner/.gradle",
        "--env", "CARGO_HOME=/home/runner/.cargo", "--env", "GOPATH=/home/runner/go",
        "--env", "GOMODCACHE=/home/runner/go/pkg/mod", "--env", "GOCACHE=/tmp/go-build-cache",
        "--env", "GOPROXY=off", "--mount", f"type=bind,src={project.resolve()},dst=/workspace,rw",
        "--workdir", "/workspace", "--entrypoint", "/bin/sh", image, "-lc", command_line
    ]
    try:
        proc = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
        try:
            output, _ = proc.communicate(timeout=max(30, min(timeout, 900)))
            code = proc.returncode
            result = {"ok": code == 0, "status": "TESTS_PASSED" if code == 0 else "TESTS_FAILED", "ecosystem": ecosystem, "description": description, "exitCode": code, "durationSeconds": round(time.monotonic() - started, 2), "output": redact(output[-MAX_OUTPUT:])}
            return complete(result)
        except subprocess.TimeoutExpired:
            proc.kill()
            try: output, _ = proc.communicate(timeout=5)
            except Exception: output = ""
            subprocess.run([runtime, "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=False)
            return complete({"ok": False, "status": "TEST_TIMEOUT", "ecosystem": ecosystem, "description": description, "exitCode": None, "durationSeconds": round(time.monotonic() - started, 2), "output": redact((output or "")[-MAX_OUTPUT:]) + "\nBuild/test exceeded the configured timeout and was stopped."})
    except Exception as error:
        return complete({"ok": False, "status": "SANDBOX_RUNTIME_FAILURE", "ecosystem": ecosystem, "description": description, "exitCode": None, "durationSeconds": round(time.monotonic() - started, 2), "output": f"Sandbox failed before a verified result: {type(error).__name__}. No successful build/test is claimed."})
    finally:
        marker.unlink(missing_ok=True)


def package_artifacts(artifact_dir: Path, output: Path) -> dict[str, Any]:
    root = artifact_dir.resolve()
    destination = output.resolve()
    if not root.is_dir():
        root.mkdir(parents=True, exist_ok=True)
    if destination.is_relative_to(root):
        raise ValueError("Artifact bundle output must be outside the artifact input directory.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    manifest: list[dict[str, Any]] = []
    total = 0
    try:
        files = sorted(root.rglob("*"))
        if len(files) > 400:
            raise ValueError("Too many build artifact files to package safely.")
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=4) as archive:
            for path in files:
                if path.is_symlink() or not path.is_file() or path == temporary:
                    continue
                rel = safe_path(path.relative_to(root).as_posix())
                if rel is None:
                    continue
                ext = path.suffix.lower()
                if ext not in {".apk", ".aab", ".xml", ".html", ".json", ".txt", ".log"}:
                    continue
                size = path.stat().st_size
                if size <= 0 or size > 450 * 1024 * 1024 or total + size > 600 * 1024 * 1024:
                    continue
                if ext in {".apk", ".aab"} and _artifact_is_sensitive(path):
                    manifest.append({"path": rel, "bytes": size, "status": "withheld_sensitive_marker"})
                    continue
                arcname = rel
                if arcname in {item["path"] for item in manifest}:
                    continue
                archive.write(path, arcname=arcname)
                digest = __import__("hashlib").sha256()
                with path.open("rb") as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(chunk)
                manifest.append({"path": rel, "bytes": size, "sha256": digest.hexdigest(), "status": "included"})
                total += size
            archive.writestr("manifest.json", json.dumps({"artifacts": manifest, "totalBytes": total}, indent=2))
        temporary.replace(destination)
        return {"ok": True, "status": "BUILD_ARTIFACTS_PACKAGED", "files": len([x for x in manifest if x.get("status") == "included"]), "bytes": destination.stat().st_size, "sha256": __import__("hashlib").sha256(destination.read_bytes()).hexdigest(), "message": f"Packaged {sum(1 for x in manifest if x.get('status') == 'included')} report/artifact file(s); {sum(1 for x in manifest if x.get('status') != 'included')} withheld or excluded."}
    finally:
        temporary.unlink(missing_ok=True)


def shell_quote(value: str) -> str:
    # Values are fixed internally; shell quoting prevents manifest-derived paths/arguments being injected.
    return "'" + value.replace("'", "'\"'\"'") + "'"


def stop_container(job_dir: Path) -> dict[str, Any]:
    marker = job_dir / ".workspace-container"
    if not marker.is_file():
        return {"ok": True, "status": "NO_ACTIVE_CONTAINER", "message": "No active container marker was found."}
    name = marker.read_text(encoding="ascii", errors="ignore").strip()
    if not re.fullmatch(r"vq-workspace-[a-f0-9]{20}", name):
        return {"ok": False, "status": "INVALID_CONTAINER_MARKER", "message": "Container marker was invalid; no command was run."}
    runtime = os.environ.get("VIBES_WORKSPACE_SANDBOX_RUNTIME", "docker").strip()
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", runtime) or shutil.which(runtime) is None:
        return {"ok": False, "status": "SANDBOX_RUNTIME_MISSING", "message": "Could not contact sandbox runtime to stop the container."}
    subprocess.run([runtime, "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=8, check=False)
    marker.unlink(missing_ok=True)
    return {"ok": True, "status": "STOP_REQUESTED", "message": "Best-effort sandbox container stop was issued."}


def package_project(project: Path, output: Path) -> dict[str, Any]:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for path in sorted(project.rglob("*")):
                if not path.is_file() or path.is_symlink():
                    continue
                rel = path.relative_to(project).as_posix()
                if should_skip(rel) or rel == ".workspace-container":
                    continue
                if path.suffix.lower() in TEXT_EXTENSIONS and path.stat().st_size <= MAX_FILE_BYTES:
                    try:
                        text = path.read_text(encoding="utf-8")
                    except (UnicodeDecodeError, OSError):
                        text = ""
                    if text and redact(text) != text:
                        raise ValueError(f"Candidate packaging blocked: possible embedded credential in {rel}. Remove the secret and start a fresh job.")
                archive.write(path, arcname=rel)
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    return {"ok": True, "status": "CANDIDATE_PACKAGED", "bytes": output.stat().st_size, "sha256": __import__("hashlib").sha256(output.read_bytes()).hexdigest()}



MAX_AUDIT_DIFF_BYTES = 2 * 1024 * 1024
MAX_AUDIT_CHANGED_FILES = 24
MAX_AUDIT_CHANGED_LINES = 900
RISK_PATH_RE = re.compile(
    r"(?i)(^|/)(\.github/workflows/|\.gitlab-ci|\.circleci/|Dockerfile|docker-compose|"
    r".*(?:build\.gradle(?:\.kts)?|settings\.gradle(?:\.kts)?|pom\.xml|package\.json|"
    r"package-lock\.json|pnpm-lock\.yaml|yarn\.lock|Cargo\.(?:toml|lock)|go\.(?:mod|sum)|"
    r"requirements.*\.txt|pyproject\.toml|AndroidManifest\.xml|.*(?:auth|security|permission|"
    r"migration|deploy|release|workflow|token|crypto|vault).*)$)"
)
TEST_PATH_RE = re.compile(r"(?i)(^|/)(tests?/|test_[^/]+\.py$|[^/]+_test\.[^/]+$|[^/]+Tests?\.[^/]+$)")


def _audit_manifest(project: Path) -> dict[str, dict[str, Any]]:
    root = project.resolve()
    if not root.is_dir():
        raise ValueError(f"Audit project is not a directory: {root}")
    manifest: dict[str, dict[str, Any]] = {}
    total = 0
    for path in sorted(root.rglob("*")):
        rel = path.relative_to(root).as_posix()
        if path.is_symlink():
            # A symlink in a candidate is an explicit review blocker; don't follow it.
            manifest[rel] = {"sha256": "SYMLINK_BLOCKED", "bytes": 0, "symlink": True}
            continue
        if not path.is_file() or should_skip(rel):
            continue
        size = path.stat().st_size
        total += size
        if len(manifest) >= MAX_FILES or total > MAX_EXPANDED_BYTES:
            raise ValueError("Project exceeds audit file-count or byte limits.")
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        manifest[rel] = {"sha256": digest.hexdigest(), "bytes": size, "symlink": False}
    return manifest


def _audit_read_text(path: Path) -> str | None:
    try:
        if path.stat().st_size > 96 * 1024:
            return None
        text = path.read_text(encoding="utf-8")
        return None if "\x00" in text else text
    except (OSError, UnicodeDecodeError):
        return None


def _audit_syntax_check(path: Path, relative: str) -> dict[str, Any] | None:
    suffix = path.suffix.lower()
    try:
        if suffix == ".py":
            ast.parse(path.read_text(encoding="utf-8"), filename=relative)
        elif suffix == ".json":
            json.loads(path.read_text(encoding="utf-8"))
        elif suffix == ".xml":
            ET.parse(path)
        elif suffix == ".toml":
            # tomllib is standard in Python 3.11+. Do not install tools or execute project code.
            try:
                import tomllib  # type: ignore
            except ImportError:
                return {"path": relative, "severity": "INFO", "code": "TOML_CHECK_UNAVAILABLE", "message": "tomllib is unavailable; TOML syntax was not checked."}
            with path.open("rb") as stream:
                tomllib.load(stream)
        elif suffix in {".sh", ".bash"}:
            shell = shutil.which("sh")
            if shell is None:
                return {"path": relative, "severity": "INFO", "code": "SHELL_CHECK_UNAVAILABLE", "message": "sh is unavailable; shell syntax was not checked."}
            # `sh -n` parses syntax only; it does not execute the script.
            check = subprocess.run([shell, "-n", str(path)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=5, check=False)
            if check.returncode != 0:
                return {"path": relative, "severity": "BLOCK", "code": "SHELL_SYNTAX_ERROR", "message": redact(check.stdout[-500:]) or "Shell parser rejected the file."}
    except (SyntaxError, ValueError, ET.ParseError, OSError, subprocess.SubprocessError) as error:
        line = getattr(error, "lineno", None)
        location = f" at line {line}" if isinstance(line, int) else ""
        return {"path": relative, "severity": "BLOCK", "code": "SYNTAX_ERROR", "message": f"{type(error).__name__}{location}: {str(error)[:240]}"}
    return None


def _audit_generate_diff(baseline: Path, candidate: Path, changed: list[str]) -> tuple[str, int]:
    chunks: list[str] = []
    changed_lines = 0
    for rel in changed:
        old_path, new_path = baseline / rel, candidate / rel
        old = _audit_read_text(old_path) if old_path.is_file() and not old_path.is_symlink() else ""
        new = _audit_read_text(new_path) if new_path.is_file() and not new_path.is_symlink() else ""
        if old is None or new is None:
            old_hash = hashlib.sha256(old_path.read_bytes()).hexdigest() if old_path.is_file() and not old_path.is_symlink() else "absent"
            new_hash = hashlib.sha256(new_path.read_bytes()).hexdigest() if new_path.is_file() and not new_path.is_symlink() else "absent"
            chunks.append(f"\n[NON-TEXT OR LARGE FILE] {rel}\nBASELINE_SHA256={old_hash}\nCANDIDATE_SHA256={new_hash}\n")
            continue
        diff_lines = list(difflib.unified_diff(
            old.splitlines(keepends=True), new.splitlines(keepends=True),
            fromfile=f"a/{rel}" if old_path.exists() else "/dev/null",
            tofile=f"b/{rel}" if new_path.exists() else "/dev/null",
            n=3,
        ))
        changed_lines += sum(1 for line in diff_lines if (line.startswith("+") and not line.startswith("+++")) or (line.startswith("-") and not line.startswith("---")))
        # All generated diff text is redacted before it can be stored or downloaded.
        # Redact the complete diff so multi-line private keys are detected across line boundaries.
        chunks.append(redact("".join(diff_lines)))
    text = "\n".join(chunks)
    if len(text.encode("utf-8", errors="replace")) > MAX_AUDIT_DIFF_BYTES:
        text = text.encode("utf-8", errors="replace")[:MAX_AUDIT_DIFF_BYTES].decode("utf-8", errors="ignore")
        text += "\n[DIFF TRUNCATED AT 2 MiB; use per-file hashes and inspect the candidate in a trusted review environment.]\n"
    return text, changed_lines


def audit_candidate(baseline: Path, candidate: Path, artifact_dir: Path) -> dict[str, Any]:
    baseline, candidate = baseline.resolve(), candidate.resolve()
    if baseline == candidate or baseline.is_relative_to(candidate) or candidate.is_relative_to(baseline):
        raise ValueError("Baseline and candidate must be separate, non-nested directories.")
    artifact_dir = artifact_dir.resolve()
    if artifact_dir.is_relative_to(baseline) or artifact_dir.is_relative_to(candidate):
        raise ValueError("Audit artifacts must be stored outside both source trees.")
    base_manifest = _audit_manifest(baseline)
    cand_manifest = _audit_manifest(candidate)
    added = sorted(cand_manifest.keys() - base_manifest.keys())
    removed = sorted(base_manifest.keys() - cand_manifest.keys())
    changed_existing = sorted(k for k in base_manifest.keys() & cand_manifest.keys() if base_manifest[k]["sha256"] != cand_manifest[k]["sha256"])
    changed = sorted(set(added + removed + changed_existing))
    diff_text, changed_lines = _audit_generate_diff(baseline, candidate, changed)
    blockers: list[dict[str, Any]] = []
    risks: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []
    syntax_checks = 0
    test_files_touched = any(TEST_PATH_RE.search(rel) for rel in changed)

    if not changed:
        blockers.append({"path": ".", "severity": "BLOCK", "code": "NO_CHANGES", "message": "Candidate does not differ from the original baseline."})
    if len(changed) > MAX_AUDIT_CHANGED_FILES:
        blockers.append({"path": ".", "severity": "BLOCK", "code": "CHANGE_SCOPE_EXCEEDED", "message": f"Candidate changes {len(changed)} files; the accumulated job limit is {MAX_AUDIT_CHANGED_FILES}."})
    if changed_lines > MAX_AUDIT_CHANGED_LINES:
        blockers.append({"path": ".", "severity": "BLOCK", "code": "CHANGE_LINE_LIMIT_EXCEEDED", "message": f"Candidate changes {changed_lines} text lines; the job limit is {MAX_AUDIT_CHANGED_LINES}."})

    for rel in changed:
        item = cand_manifest.get(rel)
        if item and item.get("symlink"):
            blockers.append({"path": rel, "severity": "BLOCK", "code": "SYMLINK_IN_CANDIDATE", "message": "Candidate contains a symbolic link; the audit did not follow it."})
            continue
        path = candidate / rel
        if not path.is_file():
            risks.append({"path": rel, "severity": "REVIEW", "code": "FILE_REMOVED", "message": "A baseline file was removed; verify callers, packaging, and migration/rollback impact."})
            continue
        if RISK_PATH_RE.search(rel):
            risks.append({"path": rel, "severity": "REVIEW", "code": "SENSITIVE_AREA_CHANGED", "message": "A build, dependency, deployment, permission, authentication, security, migration, or release-related file changed; independent review is required."})
        text = _audit_read_text(path)
        if text is not None:
            if redact(text) != text:
                blockers.append({"path": rel, "severity": "BLOCK", "code": "POSSIBLE_CREDENTIAL", "message": "Potential embedded credential detected. Value omitted; remove it from the candidate before packaging."})
            syntax = _audit_syntax_check(path, rel)
            if syntax is not None:
                (blockers if syntax["severity"] == "BLOCK" else warnings).append(syntax)
                if syntax["severity"] == "BLOCK":
                    continue
            if path.suffix.lower() in {".py", ".json", ".xml", ".toml", ".sh", ".bash"}:
                syntax_checks += 1
        elif path.suffix.lower() in TEXT_EXTENSIONS and path.stat().st_size <= MAX_FILE_BYTES:
            warnings.append({"path": rel, "severity": "INFO", "code": "TEXT_SCAN_UNAVAILABLE", "message": "File could not be safely decoded as UTF-8; syntax/credential scanning was skipped."})

    if changed and not test_files_touched:
        warnings.append({"path": ".", "severity": "REVIEW", "code": "NO_TEST_CHANGE_DETECTED", "message": "No test file changed; the reviewers should confirm existing tests adequately cover this behavior."})
    try:
        baseline_profile = describe_project(baseline)
        candidate_profile = describe_project(candidate)
        before_deps = set(baseline_profile.get("dependencies", []))
        after_deps = set(candidate_profile.get("dependencies", []))
        dependency_delta = {"added": sorted(after_deps - before_deps)[:100], "removed": sorted(before_deps - after_deps)[:100]}
        before_manifests = set(baseline_profile.get("dependencyManifests", []))
        after_manifests = set(candidate_profile.get("dependencyManifests", []))
        manifest_delta = {"added": sorted(after_manifests - before_manifests), "removed": sorted(before_manifests - after_manifests)}
    except Exception:
        baseline_profile, candidate_profile = {}, {}
        dependency_delta, manifest_delta = {"added": [], "removed": []}, {"added": [], "removed": []}

    artifact_dir.mkdir(parents=True, exist_ok=True)
    diff_path = artifact_dir / "candidate-review.diff.txt"
    report_path = artifact_dir / "candidate-audit.json"
    diff_path.write_text(diff_text or "No textual diff was generated; consult the per-file hashes in candidate-audit.json.\n", encoding="utf-8")

    status = "AUDIT_BLOCKED" if blockers else ("AUDIT_REVIEW_REQUIRED" if risks else "AUDIT_PASSED")
    summary = f"{status}: {len(changed)} changed file(s), {changed_lines} changed text line(s), {len(blockers)} blocker(s), {len(risks)} sensitive-area/removal review item(s), {len(warnings)} warning(s)."
    report = {
        "ok": not blockers,
        "status": status,
        "summary": summary,
        "baseline": str(baseline.name),
        "candidate": str(candidate.name),
        "generatedAtEpochSeconds": int(time.time()),
        "limits": {"changedFiles": MAX_AUDIT_CHANGED_FILES, "changedLines": MAX_AUDIT_CHANGED_LINES, "diffBytes": MAX_AUDIT_DIFF_BYTES},
        "counts": {"added": len(added), "removed": len(removed), "changed": len(changed), "changedLines": changed_lines, "syntaxChecks": syntax_checks, "blockers": len(blockers), "reviewItems": len(risks), "warnings": len(warnings)},
        "changedFiles": [{"path": rel, "change": "added" if rel in added else "removed" if rel in removed else "modified", "baselineSha256": base_manifest.get(rel, {}).get("sha256"), "candidateSha256": cand_manifest.get(rel, {}).get("sha256"), "bytes": cand_manifest.get(rel, {}).get("bytes", 0)} for rel in changed[:500]],
        "dependencyDelta": dependency_delta,
        "manifestDelta": manifest_delta,
        "testFilesTouched": test_files_touched,
        "baselineProfile": baseline_profile,
        "candidateProfile": candidate_profile,
        "blockers": blockers[:100],
        "reviewItems": risks[:100],
        "warnings": warnings[:100],
        "artifacts": {"diff": diff_path.name, "report": report_path.name, "diffRedacted": True},
        "note": "Static audit is a preflight aid, not proof of semantic correctness, exploit absence, successful compilation, or production readiness. Independent review and isolated build/tests remain required.",
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report

def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    inspect_p = sub.add_parser("inspect")
    inspect_p.add_argument("--archive", required=True)
    inspect_p.add_argument("--project", required=True)
    index_p = sub.add_parser("index")
    index_p.add_argument("--project", required=True)
    clone_p = sub.add_parser("clone")
    clone_p.add_argument("--project", required=True)
    clone_p.add_argument("--output", required=True)
    apply_p = sub.add_parser("apply")
    apply_p.add_argument("--project", required=True)
    apply_p.add_argument("--patch", required=True)
    test_p = sub.add_parser("test")
    test_p.add_argument("--project", required=True)
    test_p.add_argument("--timeout", type=int, default=300)
    test_p.add_argument("--artifact-dir")
    artifacts_p = sub.add_parser("artifact-package")
    artifacts_p.add_argument("--artifact-dir", required=True)
    artifacts_p.add_argument("--output", required=True)
    stop_p = sub.add_parser("stop")
    stop_p.add_argument("--job-dir", required=True)
    audit_p = sub.add_parser("audit")
    audit_p.add_argument("--baseline", required=True)
    audit_p.add_argument("--candidate", required=True)
    audit_p.add_argument("--artifact-dir", required=True)
    package_p = sub.add_parser("package")
    package_p.add_argument("--project", required=True)
    package_p.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        if args.command == "inspect":
            payload = extract_archive(Path(args.archive), Path(args.project))
        elif args.command == "index":
            payload = index_project(Path(args.project))
        elif args.command == "clone":
            payload = clone_project(Path(args.project), Path(args.output))
        elif args.command == "apply":
            payload = apply_patch(Path(args.project).resolve(), Path(args.patch).resolve())
            if not payload.get("ok"):
                emit(payload, 2)
        elif args.command == "test":
            artifact_dir = Path(args.artifact_dir).resolve() if args.artifact_dir else None
            payload = test_project(Path(args.project).resolve(), args.timeout, artifact_dir)
            # Missing sandbox and test failures are structured pipeline results, not script crashes.
        elif args.command == "stop":
            payload = stop_container(Path(args.job_dir).resolve())
        elif args.command == "audit":
            payload = audit_candidate(Path(args.baseline), Path(args.candidate), Path(args.artifact_dir))
            if not payload.get("ok"):
                emit(payload, 2)
        elif args.command == "package":
            payload = package_project(Path(args.project).resolve(), Path(args.output).resolve())
        elif args.command == "artifact-package":
            payload = package_artifacts(Path(args.artifact_dir).resolve(), Path(args.output).resolve())
        else:
            raise ValueError("Unsupported runner command.")
        emit(payload)
    except SystemExit:
        raise
    except Exception as error:
        emit({"ok": False, "status": "RUNNER_ERROR", "message": str(error)[:1000]}, 2)


if __name__ == "__main__":
    main()

if __name__ == "__main__":
    unittest.main()
