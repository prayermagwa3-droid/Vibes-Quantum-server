\
#!/usr/bin/env python3
"""Persistent candidate snapshots, regression-gated rollback, and bounded supervisor.

This module manages *staged candidate directories only*. It never edits a live app
or deploys artifacts. Use an external authenticated admin service to authorize calls.
"""
from __future__ import annotations
import argparse, hashlib, json, os, shutil, signal, time, difflib
from pathlib import Path
from typing import Callable

SKIP = {".git", ".gradle", "build", "node_modules", ".idea", "__pycache__", ".venv"}
MAX_FILES = 5000
MAX_BYTES = 250 * 1024 * 1024

def tree_manifest(root: Path) -> dict:
    root = root.resolve()
    if not root.is_dir(): raise ValueError("candidate directory does not exist")
    result, total = {}, 0
    for p in sorted(root.rglob("*")):
        rel = p.relative_to(root)
        if any(part in SKIP for part in rel.parts) or p.is_symlink() or not p.is_file(): continue
        total += p.stat().st_size
        if len(result) >= MAX_FILES or total > MAX_BYTES: raise ValueError("candidate exceeds snapshot limits")
        h = hashlib.sha256()
        with p.open("rb") as f:
            for chunk in iter(lambda: f.read(1024*1024), b""): h.update(chunk)
        result[rel.as_posix()] = {"sha256": h.hexdigest(), "bytes": p.stat().st_size}
    return result

def atomic_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, indent=2, sort_keys=True), encoding="utf-8")
    os.replace(tmp, path)

class VersionStore:
    def __init__(self, state_dir: Path):
        self.root = state_dir.resolve()
        self.snaps = self.root / "snapshots"
        self.snaps.mkdir(parents=True, exist_ok=True)
        self.ledger = self.root / "versions.json"

    def _ledger(self):
        if not self.ledger.exists(): return {"versions": [], "known_good": None}
        return json.loads(self.ledger.read_text(encoding="utf-8"))

    def snapshot(self, candidate: Path, label: str = "candidate", known_good: bool = False) -> dict:
        candidate = candidate.resolve()
        manifest = tree_manifest(candidate)
        digest = hashlib.sha256(json.dumps(manifest, sort_keys=True).encode()).hexdigest()[:16]
        version_id = f"{int(time.time())}-{digest}"
        dest = self.snaps / version_id
        if not dest.exists():
            shutil.copytree(candidate, dest, ignore=shutil.ignore_patterns(*SKIP, "*.keystore", "*.jks", "*.p12", "*.pfx", "*.pem", "*.key", ".env", "local.properties"))
        # Re-manifest copied snapshot to ensure its content is what was recorded.
        copied = tree_manifest(dest)
        if copied != manifest:
            shutil.rmtree(dest, ignore_errors=True)
            raise RuntimeError("snapshot verification failed; candidate may contain excluded files")
        row = {"id": version_id, "label": label[:100], "created_at": int(time.time()),
               "manifest": manifest, "verified": bool(known_good), "source": str(candidate)}
        data = self._ledger()
        data["versions"] = [v for v in data["versions"] if v["id"] != version_id] + [row]
        if known_good: data["known_good"] = version_id
        atomic_json(self.ledger, data)
        return row

    def compare(self, old_id: str, new_id: str) -> dict:
        data = self._ledger()
        versions = {v["id"]: v for v in data["versions"]}
        if old_id not in versions or new_id not in versions: raise ValueError("unknown snapshot id")
        a, b = versions[old_id]["manifest"], versions[new_id]["manifest"]
        return {"from": old_id, "to": new_id, "added": sorted(b.keys()-a.keys()),
                "removed": sorted(a.keys()-b.keys()),
                "changed": sorted(k for k in a.keys() & b.keys() if a[k]["sha256"] != b[k]["sha256"])}

    def rollback(self, version_id: str, destination: Path, admin_authorized: bool, regression_confirmed: bool) -> dict:
        if not admin_authorized: raise PermissionError("authorized admin required for rollback")
        if not regression_confirmed: raise PermissionError("rollback requires a confirmed regression gate")
        data = self._ledger()
        row = next((v for v in data["versions"] if v["id"] == version_id), None)
        if not row or not row.get("verified"): raise ValueError("rollback target is not a known-good snapshot")
        source = (self.snaps / version_id).resolve()
        if not source.is_dir() or not source.is_relative_to(self.snaps): raise ValueError("snapshot path invalid")
        destination = destination.resolve()
        if destination.exists(): raise FileExistsError("rollback destination must be empty/nonexistent; live tree is never overwritten")
        shutil.copytree(source, destination)
        if tree_manifest(destination) != row["manifest"]:
            shutil.rmtree(destination, ignore_errors=True)
            raise RuntimeError("rollback verification failed")
        return {"restored": version_id, "destination": str(destination), "verified": True}

class ContinuousSupervisor:
    """Persistent fail-closed cycle controller. The callback must perform one isolated cycle."""
    def __init__(self, state_path: Path, emergency_stop_path: Path, *, max_cycles=24,
                 max_failures=3, budget_limit=0.0, max_runtime_seconds=86400):
        self.state_path, self.stop_path = state_path, emergency_stop_path
        self.max_cycles, self.max_failures = max(1, max_cycles), max(1, max_failures)
        self.budget_limit, self.max_runtime_seconds = max(0.0, budget_limit), max(60, max_runtime_seconds)

    def stop(self, admin_authorized: bool):
        if not admin_authorized: raise PermissionError("authorized admin required")
        self.stop_path.parent.mkdir(parents=True, exist_ok=True)
        self.stop_path.write_text("STOP\n", encoding="utf-8")

    def run(self, cycle: Callable[[int], dict], *, admin_authorized: bool,
            interval_seconds=21600, sleep: Callable[[float], None]=time.sleep) -> dict:
        if not admin_authorized: raise PermissionError("authorized admin required to start supervisor")
        started, failures, spend, completed = time.monotonic(), 0, 0.0, 0
        state = {"status":"RUNNING", "cycles":0, "failures":0, "spend":0.0, "updated_at":int(time.time())}
        atomic_json(self.state_path, state)
        while completed < self.max_cycles and failures < self.max_failures:
            if self.stop_path.exists(): state["status"] = "EMERGENCY_STOP"; break
            if time.monotonic()-started >= self.max_runtime_seconds: state["status"] = "RUNTIME_LIMIT"; break
            try:
                outcome = cycle(completed + 1) or {}
                cost = float(outcome.get("cost", 0.0))
                if cost < 0: raise ValueError("negative cost rejected")
                spend += cost
                if self.budget_limit and spend > self.budget_limit:
                    state["status"] = "BUDGET_LIMIT"; break
                if outcome.get("status") != "READY_FOR_REVIEW": failures += 1
                else: failures = 0
                completed += 1
                state.update({"cycles":completed, "failures":failures, "spend":round(spend, 6),
                              "last_outcome":str(outcome.get("status","UNKNOWN"))[:80]})
                atomic_json(self.state_path, state)
                if completed < self.max_cycles and failures < self.max_failures:
                    # Split wait into checkpoints so emergency stop remains responsive.
                    for _ in range(max(1, int(max(1, interval_seconds)/5))):
                        if self.stop_path.exists(): break
                        sleep(min(5, max(1, interval_seconds)))
                    if self.stop_path.exists(): state["status"] = "EMERGENCY_STOP"; break
            except Exception as exc:
                failures += 1
                state.update({"failures":failures, "last_error":type(exc).__name__})
                atomic_json(self.state_path, state)
        else:
            state["status"] = "CYCLE_LIMIT" if completed >= self.max_cycles else "FAILURE_THRESHOLD"
        state.update({"cycles":completed, "failures":failures, "spend":round(spend,6), "updated_at":int(time.time())})
        atomic_json(self.state_path, state)
        return state

def main():
    ap=argparse.ArgumentParser()
    sub=ap.add_subparsers(dest="cmd", required=True)
    for cmd in ("snapshot","list"):
        p=sub.add_parser(cmd); p.add_argument("--state", required=True)
        if cmd=="snapshot":
            p.add_argument("--project", required=True); p.add_argument("--label", default="candidate")
            p.add_argument("--known-good", action="store_true")
    c=sub.add_parser("compare"); c.add_argument("--state",required=True); c.add_argument("--from-id",required=True); c.add_argument("--to-id",required=True)
    r=sub.add_parser("rollback"); r.add_argument("--state",required=True); r.add_argument("--version",required=True); r.add_argument("--destination",required=True); r.add_argument("--admin-token",required=True); r.add_argument("--expected-admin-token",required=True); r.add_argument("--regression-confirmed",action="store_true")
    args=ap.parse_args(); store=VersionStore(Path(args.state))
    try:
        if args.cmd=="snapshot": result=store.snapshot(Path(args.project),args.label,args.known_good)
        elif args.cmd=="list": result=store._ledger()
        elif args.cmd=="compare": result=store.compare(args.from_id,args.to_id)
        else: result=store.rollback(args.version,Path(args.destination),bool(args.admin_token and args.admin_token==args.expected_admin_token),args.regression_confirmed)
        print(json.dumps(result,indent=2)); return 0
    except Exception as exc:
        print(json.dumps({"ok":False,"error":str(exc)})); return 2
if __name__=="__main__": raise SystemExit(main())
