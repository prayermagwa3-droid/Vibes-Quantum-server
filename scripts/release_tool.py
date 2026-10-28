#!/usr/bin/env python3
"""Small, auditable release, snapshot and SQLite migration helper for Vibes Quantum.

Never logs environment values or request bodies. Intended for staging/local use until
adapted to the actual production database and deployment platform.
"""
from __future__ import annotations
import argparse, hashlib, json, os, re, shutil, sqlite3, subprocess, sys, tempfile, time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
RELEASE_DIR = ROOT / "release"
DEFAULT_MANIFEST = RELEASE_DIR / "release-manifest.json"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def safe_relative(root: Path, candidate: Path) -> Path:
    resolved_root = root.resolve()
    resolved = (root / candidate).resolve() if not candidate.is_absolute() else candidate.resolve()
    if resolved != resolved_root and resolved_root not in resolved.parents:
        raise ValueError(f"Path escapes release root: {candidate}")
    return resolved


def git_revision(root: Path) -> str:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, stderr=subprocess.DEVNULL, text=True, timeout=3).strip()
    except Exception:
        return "unavailable-not-a-git-checkout"


def build_manifest(root: Path, output: Path) -> dict[str, Any]:
    base = json.loads((root / "release/release-manifest.json").read_text(encoding="utf-8"))
    revision = git_revision(root)
    base["created_utc"] = utc_now()
    files: dict[str, str] = {}
    excluded = {".git", ".gradle", "build", ".idea", "__pycache__", ".venv", "venv"}
    for path in sorted(root.rglob("*")):
        if not path.is_file() or any(part in excluded for part in path.parts):
            continue
        if path.name in {"candidate-test-report.json", "rollback-drill-report.json", "release-gate-result.json", "quality-gate-report.json"}:
            continue
        if path.resolve() == output.resolve():
            continue
        try:
            rel = path.relative_to(root).as_posix()
            files[rel] = sha256_file(path)
        except (OSError, ValueError):
            continue
    if revision == "unavailable-not-a-git-checkout":
        canonical = "\n".join(f"{name}:{digest}" for name, digest in sorted(files.items())).encode("utf-8")
        revision = "tree-sha256:" + hashlib.sha256(canonical).hexdigest()
    base["source_revision"] = revision
    base["artifact_integrity"] = {"algorithm": "sha256", "files": files, "file_count": len(files)}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(base, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return base


def verify_manifest(manifest_path: Path, root: Path) -> list[str]:
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    errors: list[str] = []
    revision = str(data.get("source_revision", ""))
    if not (re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", revision) or re.fullmatch(r"tree-sha256:[0-9a-fA-F]{64}", revision)):
        errors.append("source_revision must be a Git commit SHA or source-tree SHA-256")
    if data.get("created_utc") in (None, "", "SET_BY_RELEASE_PIPELINE"):
        errors.append("created_utc is not set")
    integrity = data.get("artifact_integrity", {})
    files = integrity.get("files", {})
    if not files:
        errors.append("artifact SHA-256 inventory is missing")
    for rel, expected in files.items():
        try:
            path = safe_relative(root, Path(rel))
            if not path.is_file():
                errors.append(f"missing artifact file: {rel}")
            elif sha256_file(path) != expected:
                errors.append(f"checksum mismatch: {rel}")
        except (ValueError, OSError):
            errors.append(f"unsafe/unreadable artifact path: {rel}")
    return errors


def create_snapshot(paths: list[Path], destination: Path, root: Path) -> dict[str, Any]:
    destination.mkdir(parents=True, exist_ok=False)
    entries = []
    for src in paths:
        src = src.resolve()
        if not src.exists():
            continue
        if src.is_symlink():
            raise ValueError(f"Refusing to snapshot symlink: {src}")
        rel = src.relative_to(root.resolve()) if root.resolve() in src.parents else Path(src.name)
        dest = safe_relative(destination, rel)
        if src.is_dir():
            shutil.copytree(src, dest, symlinks=False)
            for item in dest.rglob("*"):
                if item.is_file(): entries.append({"path": item.relative_to(destination).as_posix(), "sha256": sha256_file(item), "bytes": item.stat().st_size})
        else:
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dest)
            entries.append({"path": dest.relative_to(destination).as_posix(), "sha256": sha256_file(dest), "bytes": dest.stat().st_size})
    manifest = {"snapshot_schema": 1, "created_utc": utc_now(), "root": str(root.resolve()), "entries": entries}
    (destination / "snapshot-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def verify_snapshot(snapshot: Path) -> list[str]:
    data = json.loads((snapshot / "snapshot-manifest.json").read_text(encoding="utf-8"))
    errors = []
    for entry in data.get("entries", []):
        try:
            path = safe_relative(snapshot, Path(entry["path"]))
            if not path.is_file(): errors.append(f"missing: {entry['path']}")
            elif sha256_file(path) != entry["sha256"]: errors.append(f"checksum mismatch: {entry['path']}")
        except (ValueError, OSError, KeyError): errors.append(f"unsafe entry: {entry.get('path', '?')}")
    return errors


def restore_snapshot(snapshot: Path, destination_root: Path) -> int:
    errors = verify_snapshot(snapshot)
    if errors: raise ValueError("Snapshot verification failed: " + "; ".join(errors))
    data = json.loads((snapshot / "snapshot-manifest.json").read_text(encoding="utf-8"))
    restored = 0
    for entry in data.get("entries", []):
        rel = Path(entry["path"])
        src = safe_relative(snapshot, rel)
        dst = safe_relative(destination_root, rel)
        if not src.is_file(): continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        with src.open("rb") as inp, tempfile.NamedTemporaryFile(dir=dst.parent, delete=False) as tmp:
            shutil.copyfileobj(inp, tmp)
            temp_path = Path(tmp.name)
        os.replace(temp_path, dst)
        if sha256_file(dst) != entry["sha256"]: raise IOError(f"Restore verification failed: {rel}")
        restored += 1
    return restored


def migration_files(directory: Path) -> list[tuple[int, str, Path]]:
    found = []
    for path in directory.glob("*.up.sql"):
        prefix = path.name.split("_", 1)[0]
        if not prefix.isdigit(): raise ValueError(f"Invalid migration name: {path.name}")
        found.append((int(prefix), path.name, path))
    found.sort()
    if len({n for n, _, _ in found}) != len(found): raise ValueError("Duplicate migration numbers")
    return found


def migrate(db: Path, directory: Path, direction: str, backup_dir: Path) -> list[str]:
    db.parent.mkdir(parents=True, exist_ok=True)
    if not db.exists() and direction == "down": raise FileNotFoundError(db)
    backup_dir.mkdir(parents=True, exist_ok=True)
    backup = backup_dir / f"{db.stem}-{int(time.time())}-{direction}.sqlite3"
    if db.exists(): shutil.copy2(db, backup)
    applied = []
    con = sqlite3.connect(db)
    try:
        con.execute("PRAGMA foreign_keys=ON")
        con.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_utc TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
        rows = {int(row[0]): row[1] for row in con.execute("SELECT version,name FROM schema_migrations")}
        files = migration_files(directory)
        if direction == "up":
            for number, name, path in files:
                if number in rows: continue
                sql = path.read_text(encoding="utf-8")
                con.executescript("BEGIN IMMEDIATE;\n" + sql + f"\nINSERT INTO schema_migrations(version,name) VALUES({number},{json.dumps(name)});\nCOMMIT;")
                applied.append(name)
        else:
            for number, name, path in reversed(files):
                if number not in rows: continue
                down = directory / name.replace(".up.sql", ".down.sql")
                if not down.is_file(): raise FileNotFoundError(f"Missing rollback migration: {down.name}")
                sql = down.read_text(encoding="utf-8")
                con.executescript("BEGIN IMMEDIATE;\n" + sql + f"\nDELETE FROM schema_migrations WHERE version={number};\nCOMMIT;")
                applied.append(down.name)
                break  # one-step rollback per invocation
    finally:
        con.close()
    return applied


def gate(report_path: Path, manifest_path: Path, root: Path) -> tuple[bool, dict[str, Any]]:
    policy = json.loads((root / "release/release-policy.json").read_text(encoding="utf-8"))
    report = json.loads(report_path.read_text(encoding="utf-8"))
    statuses = report.get("checks", {})
    def check_status(key: str) -> Any:
        value = statuses.get(key)
        return value.get("status") if isinstance(value, dict) else value
    missing = [key for key in policy["critical_checks"] if check_status(key) != "passed"]
    manifest_errors = verify_manifest(manifest_path, root)
    comparison = report.get("baseline_comparison", {})
    thresholds = {"quality_score": {"direction": "higher", "max_regression_percent": 2.0}, "p95_latency_ms": {"direction": "lower", "max_regression_percent": 20.0}, "cost_per_1000_requests": {"direction": "lower", "max_regression_percent": 10.0}}
    comparison_results = {}
    for key, rule in thresholds.items():
        item = comparison.get(key)
        if not isinstance(item, dict) or item.get("baseline") is None or item.get("candidate") is None:
            missing.append("baseline_comparison." + key + " baseline/candidate values unavailable")
            comparison_results[key] = {"status": "unavailable"}
            continue
        try:
            baseline, candidate = float(item["baseline"]), float(item["candidate"])
            if baseline <= 0 or candidate < 0: raise ValueError("metrics must be positive/nonnegative")
            if rule["direction"] == "higher": regression = max(0.0, (baseline-candidate)/baseline*100.0)
            else: regression = max(0.0, (candidate-baseline)/baseline*100.0)
            ok = regression <= rule["max_regression_percent"]
            comparison_results[key] = {"status": "passed" if ok else "failed", "baseline": baseline, "candidate": candidate, "regression_percent": round(regression, 3), "max_regression_percent": rule["max_regression_percent"]}
            if not ok: missing.append("baseline_comparison." + key + " regression exceeds threshold")
        except (TypeError, ValueError):
            missing.append("baseline_comparison." + key + " invalid values")
            comparison_results[key] = {"status": "failed"}
    if check_status("source_manifest") != "passed": missing.append("source_manifest check is not passed")
    result = {"decision": "DENY" if missing or manifest_errors else "ELIGIBLE_FOR_HUMAN_APPROVAL", "failed_or_missing_critical_checks": missing, "manifest_errors": manifest_errors, "baseline_comparison": comparison_results, "created_utc": utc_now(), "note": "Eligibility is not deployment. Human approval and a staging rollback drill are still required."}
    return result["decision"] != "DENY", result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("manifest"); p.add_argument("--root", type=Path, default=ROOT); p.add_argument("--output", type=Path, default=RELEASE_DIR / "artifact-manifest.json")
    p = sub.add_parser("verify-manifest"); p.add_argument("--root", type=Path, default=ROOT); p.add_argument("--manifest", type=Path, default=RELEASE_DIR / "artifact-manifest.json")
    p = sub.add_parser("snapshot"); p.add_argument("--root", type=Path, default=ROOT); p.add_argument("--destination", type=Path, required=True); p.add_argument("paths", nargs="+", type=Path)
    p = sub.add_parser("verify-snapshot"); p.add_argument("snapshot", type=Path)
    p = sub.add_parser("restore"); p.add_argument("snapshot", type=Path); p.add_argument("destination_root", type=Path)
    p = sub.add_parser("migrate"); p.add_argument("database", type=Path); p.add_argument("--directory", type=Path, default=ROOT / "migrations"); p.add_argument("--backup-dir", type=Path, required=True); p.add_argument("--direction", choices=["up", "down"], default="up")
    p = sub.add_parser("gate"); p.add_argument("report", type=Path); p.add_argument("--root", type=Path, default=ROOT); p.add_argument("--manifest", type=Path, default=RELEASE_DIR / "artifact-manifest.json"); p.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "manifest":
            result = build_manifest(args.root.resolve(), args.output.resolve()); print(json.dumps({"manifest": str(args.output), "files": result["artifact_integrity"]["file_count"], "source_revision": result["source_revision"]}, indent=2)); return 0
        if args.command == "verify-manifest":
            errors = verify_manifest(args.manifest.resolve(), args.root.resolve()); print(json.dumps({"valid": not errors, "errors": errors}, indent=2)); return 0 if not errors else 2
        if args.command == "snapshot":
            paths = [safe_relative(args.root.resolve(), p) for p in args.paths]; result = create_snapshot(paths, args.destination.resolve(), args.root.resolve()); print(json.dumps({"snapshot": str(args.destination), "entries": len(result['entries'])}, indent=2)); return 0
        if args.command == "verify-snapshot":
            errors = verify_snapshot(args.snapshot.resolve()); print(json.dumps({"valid": not errors, "errors": errors}, indent=2)); return 0 if not errors else 2
        if args.command == "restore":
            print(json.dumps({"restored_files": restore_snapshot(args.snapshot.resolve(), args.destination_root.resolve())}, indent=2)); return 0
        if args.command == "migrate":
            applied = migrate(args.database.resolve(), args.directory.resolve(), args.direction, args.backup_dir.resolve()); print(json.dumps({"direction": args.direction, "migrations": applied, "backup_dir": str(args.backup_dir)}, indent=2)); return 0
        if args.command == "gate":
            ok, result = gate(args.report.resolve(), args.manifest.resolve(), args.root.resolve()); args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8"); print(json.dumps(result, indent=2)); return 0 if ok else 3
    except Exception as exc:
        print(f"release-tool error: {type(exc).__name__}: {exc}", file=sys.stderr); return 2
    return 2

if __name__ == "__main__": raise SystemExit(main())
