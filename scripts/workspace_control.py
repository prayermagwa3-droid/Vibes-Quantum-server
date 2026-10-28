#!/usr/bin/env python3
"""Persistent candidate snapshots/rollback and continuous-upgrade safety controls.

Mutating operations require VIBES_WORKSPACE_ADMIN_TOKEN. The supervisor stores its
state in SQLite and fails closed when emergency stop is latched, budgets are exceeded,
or consecutive failures hit the configured threshold. It does not deploy candidates.
"""
from __future__ import annotations
import argparse, hashlib, hmac, json, os, shutil, sqlite3, sys, time, uuid
from pathlib import Path

SKIP = {'.git', '.gradle', '.idea', 'build', 'node_modules', 'dist', 'target', '__pycache__', '.venv'}
SECRET_NAMES = {'local.properties', 'google-services.json', 'credentials.json', 'service-account.json', '.env', '.env.local'}

def sha(path: Path) -> str:
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024), b''): h.update(b)
    return h.hexdigest()

def safe_files(root: Path):
    root=root.resolve()
    for p in sorted(root.rglob('*')):
        if p.is_symlink() or not p.is_file(): continue
        rel=p.relative_to(root)
        if any(part in SKIP for part in rel.parts) or p.name == 'snapshot-manifest.json' or p.name.lower() in SECRET_NAMES or p.suffix.lower() in {'.jks','.keystore','.p12','.pfx','.pem','.key'}: continue
        resolved=p.resolve()
        if not resolved.is_relative_to(root): continue
        yield p, rel.as_posix()

def manifest(root: Path) -> dict:
    return {rel: {'sha256':sha(p),'bytes':p.stat().st_size} for p,rel in safe_files(root)}

def copy_tree(source: Path, dest: Path):
    source=source.resolve(); dest=dest.resolve()
    if not source.is_dir(): raise ValueError('Project directory does not exist.')
    if dest == source or dest.is_relative_to(source) or source.is_relative_to(dest): raise ValueError('Snapshot/source paths may not overlap.')
    if dest.exists(): raise ValueError('Destination already exists; snapshots are immutable.')
    dest.mkdir(parents=True)
    for p, rel in safe_files(source):
        target=dest/rel; target.parent.mkdir(parents=True,exist_ok=True); shutil.copyfile(p,target)
    return manifest(dest)

def snapshot(project: Path, store: Path, label: str, known_good: bool=False) -> dict:
    if not label or len(label)>100: raise ValueError('Snapshot label must be 1–100 characters.')
    store.mkdir(parents=True,exist_ok=True); sid=time.strftime('%Y%m%dT%H%M%SZ',time.gmtime())+'-'+uuid.uuid4().hex[:8]
    target=store/sid; files=copy_tree(project,target); payload={'id':sid,'label':label,'createdAt':int(time.time()),'knownGood':bool(known_good),'fileCount':len(files),'treeSha256':hashlib.sha256(json.dumps(files,sort_keys=True,separators=(',',':')).encode()).hexdigest(),'files':files}
    (target/'snapshot-manifest.json').write_text(json.dumps(payload,indent=2),encoding='utf-8')
    return {'ok':True, **{k:v for k,v in payload.items() if k!='files'}}

def compare(a: Path,b: Path) -> dict:
    ma,mb=manifest(a),manifest(b); added=sorted(mb.keys()-ma.keys()); removed=sorted(ma.keys()-mb.keys()); changed=sorted(k for k in ma.keys()&mb.keys() if ma[k]['sha256']!=mb[k]['sha256'])
    return {'ok':True,'status':'REGRESSION_CANDIDATE' if removed or changed else 'NO_SOURCE_REGRESSION_DETECTED','added':added[:500],'removed':removed[:500],'changed':changed[:500],'counts':{'added':len(added),'removed':len(removed),'changed':len(changed)},'baselineFiles':len(ma),'candidateFiles':len(mb),'note':'Source comparison is not a substitute for build/tests or semantic regression evaluation.'}

def rollback(snapshot_dir: Path, destination: Path) -> dict:
    snapshot_dir=snapshot_dir.resolve(); destination=destination.resolve()
    meta=snapshot_dir/'snapshot-manifest.json'
    if not meta.is_file(): raise ValueError('Snapshot manifest missing; rollback refused.')
    data=json.loads(meta.read_text(encoding='utf-8'))
    expected=data.get('files',{})
    actual=manifest(snapshot_dir)
    actual.pop('snapshot-manifest.json',None)
    if actual!=expected: raise ValueError('Snapshot integrity check failed; rollback refused.')
    if destination.exists(): raise ValueError('Rollback destination must be a new path; never overwrite a live project.')
    files=copy_tree(snapshot_dir,destination)
    if files!=expected: shutil.rmtree(destination,ignore_errors=True); raise ValueError('Restored tree hash differs; rollback removed.')
    return {'ok':True,'status':'ROLLBACK_RESTORED_TO_NEW_CANDIDATE','snapshotId':data['id'],'destination':str(destination),'fileCount':len(files),'requiresReview':True,'deployed':False}

def connect(db: Path):
    db.parent.mkdir(parents=True,exist_ok=True)
    c=sqlite3.connect(db,timeout=10)
    c.execute('PRAGMA journal_mode=WAL'); c.execute('PRAGMA synchronous=FULL')
    c.execute('CREATE TABLE IF NOT EXISTS supervisor (id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL DEFAULT 0, emergency_stop INTEGER NOT NULL DEFAULT 0, interval_seconds INTEGER NOT NULL DEFAULT 21600, max_cycles INTEGER NOT NULL DEFAULT 0, cycles INTEGER NOT NULL DEFAULT 0, consecutive_failures INTEGER NOT NULL DEFAULT 0, failure_threshold INTEGER NOT NULL DEFAULT 3, budget_usd REAL NOT NULL DEFAULT 0, spent_usd REAL NOT NULL DEFAULT 0, max_seconds INTEGER NOT NULL DEFAULT 1800, updated_at INTEGER NOT NULL)')
    c.execute('CREATE TABLE IF NOT EXISTS supervisor_alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, level TEXT NOT NULL, code TEXT NOT NULL, message TEXT NOT NULL)')
    c.execute('INSERT OR IGNORE INTO supervisor(id,updated_at) VALUES(1,?)',(int(time.time()),)); c.commit(); return c

def admin():
    expected=os.environ.get('VIBES_WORKSPACE_ADMIN_TOKEN',''); supplied=os.environ.get('VIBES_WORKSPACE_ADMIN_TOKEN_PROVIDED','')
    if not expected or not supplied or not hmac.compare_digest(expected,supplied): raise PermissionError('Admin token missing/invalid; state was not changed.')

def supervisor(db: Path, action: str, args) -> dict:
    if action in {'start','stop','emergency-stop','clear-emergency','configure','record'}: admin()
    c=connect(db)
    try:
        row=c.execute('SELECT enabled,emergency_stop,interval_seconds,max_cycles,cycles,consecutive_failures,failure_threshold,budget_usd,spent_usd,max_seconds,updated_at FROM supervisor WHERE id=1').fetchone()
        keys=['enabled','emergencyStop','intervalSeconds','maxCycles','cycles','consecutiveFailures','failureThreshold','budgetUsd','spentUsd','maxSeconds','updatedAt']; state=dict(zip(keys,row))
        if action=='status':
            alerts=[{'createdAt':r[0],'level':r[1],'code':r[2],'message':r[3]} for r in c.execute('SELECT created_at,level,code,message FROM supervisor_alerts ORDER BY id DESC LIMIT 10').fetchall()]
            return {'ok':True,**state,'alerts':alerts,'canRun':bool(state['enabled'] and not state['emergencyStop'] and (not state['maxCycles'] or state['cycles']<state['maxCycles']) and (not state['budgetUsd'] or state['spentUsd']<state['budgetUsd']) and state['consecutiveFailures']<state['failureThreshold'])}
        if action=='start':
            if state['emergencyStop']: raise ValueError('Emergency stop is latched; admin must explicitly clear it first.')
            c.execute('UPDATE supervisor SET enabled=1,updated_at=? WHERE id=1',(int(time.time()),))
        elif action=='stop': c.execute('UPDATE supervisor SET enabled=0,updated_at=? WHERE id=1',(int(time.time()),))
        elif action=='emergency-stop':
            c.execute('UPDATE supervisor SET enabled=0,emergency_stop=1,updated_at=? WHERE id=1',(int(time.time()),))
            c.execute('INSERT INTO supervisor_alerts(created_at,level,code,message) VALUES(?,?,?,?)',(int(time.time()),'CRITICAL','EMERGENCY_STOP','Admin latched the persistent emergency stop; continuous runs must not start until an admin clears it.'))
        elif action=='clear-emergency':
            c.execute('UPDATE supervisor SET emergency_stop=0,enabled=0,updated_at=? WHERE id=1',(int(time.time()),))
            c.execute('INSERT INTO supervisor_alerts(created_at,level,code,message) VALUES(?,?,?,?)',(int(time.time()),'INFO','EMERGENCY_CLEARED','Admin cleared the emergency stop. Continuous mode remains disabled until explicitly started.'))
        elif action=='configure':
            if args.interval < 60 or args.max_cycles < 0 or args.failure_threshold < 1 or args.budget_usd < 0 or args.max_seconds < 30: raise ValueError('Unsafe supervisor limits: interval>=60s, max cycles>=0, failure threshold>=1, budget>=0, max seconds>=30 required.')
            c.execute('UPDATE supervisor SET interval_seconds=?,max_cycles=?,failure_threshold=?,budget_usd=?,max_seconds=?,updated_at=? WHERE id=1',(args.interval,args.max_cycles,args.failure_threshold,args.budget_usd,args.max_seconds,int(time.time())))
        elif action=='record':
            cost=max(0.0,args.cost_usd); ok=bool(args.success); duration=max(0,int(args.duration_seconds))
            failures=0 if ok else state['consecutiveFailures']+1
            reason=None
            if failures >= state['failureThreshold']: reason=('FAILURE_THRESHOLD',f'Continuous mode paused after {failures} consecutive failed cycles.')
            elif state['budgetUsd']>0 and state['spentUsd']+cost >= state['budgetUsd']: reason=('BUDGET_LIMIT',f'Continuous mode paused at estimated spend ${state["spentUsd"]+cost:.4f} against configured budget ${state["budgetUsd"]:.4f}.')
            elif state['maxCycles']>0 and state['cycles']+1 >= state['maxCycles']: reason=('CYCLE_LIMIT',f'Continuous mode paused after reaching {state["maxCycles"]} configured cycles.')
            elif duration>state['maxSeconds']: reason=('CYCLE_TIMEOUT',f'Continuous mode paused because cycle duration {duration}s exceeded {state["maxSeconds"]}s.')
            c.execute('UPDATE supervisor SET cycles=cycles+1,spent_usd=spent_usd+?,consecutive_failures=?,enabled=CASE WHEN ? IS NOT NULL THEN 0 ELSE enabled END,updated_at=? WHERE id=1',(cost,failures,reason[0] if reason else None,int(time.time())))
            if reason: c.execute('INSERT INTO supervisor_alerts(created_at,level,code,message) VALUES(?,?,?,?)',(int(time.time()),'WARNING',reason[0],reason[1]))
        else: raise ValueError('Unsupported supervisor action.')
        c.commit(); updated=c.execute('SELECT enabled,emergency_stop,interval_seconds,max_cycles,cycles,consecutive_failures,failure_threshold,budget_usd,spent_usd,max_seconds,updated_at FROM supervisor WHERE id=1').fetchone(); state=dict(zip(keys,updated))
        alerts=[{'createdAt':r[0],'level':r[1],'code':r[2],'message':r[3]} for r in c.execute('SELECT created_at,level,code,message FROM supervisor_alerts ORDER BY id DESC LIMIT 10').fetchall()]
        return {'ok':True,**state,'alerts':alerts,'canRun':bool(state['enabled'] and not state['emergencyStop'] and (not state['maxCycles'] or state['cycles']<state['maxCycles']) and (not state['budgetUsd'] or state['spentUsd']<state['budgetUsd']) and state['consecutiveFailures']<state['failureThreshold'])}
    finally: c.close()

def main():
    p=argparse.ArgumentParser(); s=p.add_subparsers(dest='cmd',required=True)
    snap=s.add_parser('snapshot'); snap.add_argument('--project',required=True); snap.add_argument('--store',required=True); snap.add_argument('--label',required=True); snap.add_argument('--known-good',action='store_true')
    cmp=s.add_parser('compare'); cmp.add_argument('--baseline',required=True); cmp.add_argument('--candidate',required=True)
    rb=s.add_parser('rollback'); rb.add_argument('--snapshot',required=True); rb.add_argument('--destination',required=True)
    sup=s.add_parser('supervisor'); sup.add_argument('action',choices=['status','start','stop','emergency-stop','clear-emergency','configure','record']); sup.add_argument('--db',default=os.environ.get('VIBES_WORKSPACE_CONTROL_DB','/var/lib/vibes/workspace-control.sqlite3')); sup.add_argument('--interval',type=int,default=21600); sup.add_argument('--max-cycles',type=int,default=0); sup.add_argument('--failure-threshold',type=int,default=3); sup.add_argument('--budget-usd',type=float,default=0); sup.add_argument('--max-seconds',type=int,default=1800); sup.add_argument('--success',action='store_true'); sup.add_argument('--cost-usd',type=float,default=0); sup.add_argument('--duration-seconds',type=int,default=0)
    a=p.parse_args()
    try:
        if a.cmd=='snapshot': out=snapshot(Path(a.project),Path(a.store),a.label,a.known_good)
        elif a.cmd=='compare': out=compare(Path(a.baseline),Path(a.candidate))
        elif a.cmd=='rollback': out=rollback(Path(a.snapshot),Path(a.destination))
        else:
            out=supervisor(Path(a.db),a.action,a)
        print(json.dumps(out,sort_keys=True)); sys.exit(0)
    except Exception as e:
        print(json.dumps({'ok':False,'error':str(e)})); sys.exit(2)
if __name__=='__main__': main()
