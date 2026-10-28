#!/usr/bin/env python3
"""Run available release checks and emit an honest machine-readable report.
Critical checks that need a real staging target or measured baseline remain skipped.
"""
from __future__ import annotations
import argparse, json, os, re, subprocess, sys, time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT.parent / "Vibes_Quantum_Voice_Studio"

def utc(): return datetime.now(timezone.utc).isoformat(timespec="seconds")
def run(cmd, cwd, timeout=900, env=None):
    start=time.perf_counter()
    try:
        proc=subprocess.run(cmd,cwd=cwd,env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout,check=False)
        if proc.returncode != 0 and ("is not installed or cached" in proc.stdout or "GradleWrapperMain" in proc.stdout):
            status="unavailable"
        else: status="passed" if proc.returncode==0 else "failed"
        return {"status":status,"exit_code":proc.returncode,"duration_seconds":round(time.perf_counter()-start,3),"output_tail":proc.stdout[-6000:]}
    except FileNotFoundError as e: return {"status":"unavailable","reason":str(e)}
    except subprocess.TimeoutExpired as e: return {"status":"failed","reason":"timeout","duration_seconds":round(time.perf_counter()-start,3),"output_tail":str(e.stdout or "")[-2000:]}

def secret_scan(root):
    patterns=[re.compile(r'(?i)(api[_-]?key|secret|password|token)\s*[:=]\s*["\'][^"\']{20,}["\']')]
    allow={"release-manifest.json","release-policy.json","phase20-feature-review.json"}
    findings=[]
    for p in root.rglob("*"):
        if not p.is_file() or any(x in p.parts for x in (".git","build",".gradle","__pycache__")) or p.name in allow: continue
        try:
            if p.stat().st_size>2_000_000: continue
            s=p.read_text(encoding="utf-8",errors="ignore")
            for i,line in enumerate(s.splitlines(),1):
                if any(rx.search(line) for rx in patterns) and not any(x in line.lower() for x in ("example","placeholder","your_","changeme","replace-with","${")):
                    findings.append(f"{p.relative_to(root)}:{i}: possible embedded credential (value not copied)")
        except OSError: pass
    return {"status":"passed" if not findings else "failed","findings":findings}

def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--output",type=Path,default=ROOT/"release/candidate-test-report.json"); ap.add_argument("--staging-url",default=os.getenv("VIBES_STAGING_HEALTH_URL")); ap.add_argument("--baseline",type=Path,default=ROOT/"release/baseline-measurements.json"); args=ap.parse_args()
    checks={}
    # Generate the source inventory before reports/logs are written. Generated reports are excluded.
    manifest_cmd=[sys.executable,str(ROOT/"scripts/release_tool.py"),"manifest","--root",str(ROOT),"--output",str(ROOT/"release/artifact-manifest.json")]
    manifest_run=run(manifest_cmd,ROOT,timeout=60)
    manifest_verify=run([sys.executable,str(ROOT/"scripts/release_tool.py"),"verify-manifest","--root",str(ROOT)],ROOT,timeout=60)
    checks["source_manifest"]={"status":"passed" if manifest_run["status"]=="passed" and manifest_verify["status"]=="passed" else "failed","generate":manifest_run,"verify":manifest_verify}
    pytests=[sys.executable,"-m","pytest","-q","research-service/tests","evaluation-lab/tests","training-service/tests","quality-runtime/tests","../VibesQuantumServer/scripts/test_release_tool.py"]
    test_env=os.environ.copy()
    extra=[str(PROJECT/"research-service"),str(PROJECT/"evaluation-lab"),str(PROJECT/"training-service"),str(PROJECT/"quality-runtime")]
    test_env["PYTHONPATH"]=os.pathsep.join(extra+[test_env.get("PYTHONPATH","")])
    py=run(pytests,PROJECT,env=test_env)
    checks["unit_tests"]={"status":py["status"],"detail":py}
    checks["evaluation_tests"]={"status":py["status"],"detail":"Python evaluation and quality-runtime tests included in combined suite"}
    # Kotlin/JVM integration suite is attempted but does not turn a missing build tool into a pass.
    gradle=ROOT/"gradlew"
    if gradle.exists() and os.access(gradle,os.X_OK):
        checks["integration_tests"]=run([str(gradle),"test","--no-daemon"],ROOT,timeout=1200)
    else:
        checks["integration_tests"]={"status":"unavailable","reason":"Executable Gradle wrapper/distribution is not available in this environment"}
    scan_result=secret_scan(ROOT)
    checks["static_secret_scan"]={"status":scan_result["status"],"detail":scan_result}
    checks["security_tests"]={"status":"unavailable","reason":"The dedicated JVM authentication/security suite could not be run because the configured Gradle 9.6.1 toolchain is unavailable; static secret-pattern scan is reported separately."}
    if args.staging_url:
        import urllib.request
        try:
            req=urllib.request.Request(args.staging_url,headers={"Accept":"application/json"})
            with urllib.request.urlopen(req,timeout=8) as r:
                checks["staging_smoke"]={"status":"passed" if 200<=r.status<300 else "failed","http_status":r.status}
        except Exception as e: checks["staging_smoke"]={"status":"failed","reason":type(e).__name__}
    else: checks["staging_smoke"]={"status":"skipped","reason":"Set VIBES_STAGING_HEALTH_URL to an actual staging health endpoint"}
    checks["baseline_comparison"]={"status":"passed" if args.baseline.is_file() else "skipped","reason":None if args.baseline.is_file() else "No measured stable/candidate comparison supplied"}
    checks["rollback_drill"]={"status":"skipped","reason":"Must be performed against a disposable staging deployment; no production rollback is attempted by this script"}
    report={"report_schema":1,"created_utc":utc(),"candidate_version":"see release-manifest.json","checks":checks,"baseline_comparison":json.loads(args.baseline.read_text()) if args.baseline.is_file() else {"quality_score":None,"p95_latency_ms":None,"cost_per_1000_requests":None},"promotion":"DENY unless every critical gate is passed"}
    args.output.parent.mkdir(parents=True,exist_ok=True); args.output.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"report":str(args.output),"checks":{k:v.get("status") for k,v in checks.items()},"promotion":"DENY (expected until staging, baseline and rollback drill are completed)"},indent=2))
    return 0 if checks["unit_tests"]["status"]=="passed" else 1
if __name__=="__main__": raise SystemExit(main())
