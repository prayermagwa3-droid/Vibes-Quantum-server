from __future__ import annotations
import importlib.util, os, tempfile, unittest
from pathlib import Path
SPEC=importlib.util.spec_from_file_location('workspace_control',Path(__file__).with_name('workspace_control.py'))
mod=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(mod)

class SnapshotTests(unittest.TestCase):
    def setUp(self): self.temp=tempfile.TemporaryDirectory(); self.root=Path(self.temp.name); self.source=self.root/'source'; self.source.mkdir(); (self.source/'Main.kt').write_text('one\n'); (self.source/'local.properties').write_text('sdk.dir=/secret\n')
    def tearDown(self): self.temp.cleanup()
    def test_snapshot_skips_secrets_and_records_hash(self):
        out=mod.snapshot(self.source,self.root/'snapshots','baseline',True); p=self.root/'snapshots'/out['id']; self.assertEqual(out['fileCount'],1); self.assertFalse((p/'local.properties').exists()); self.assertTrue((p/'snapshot-manifest.json').exists())
    def test_compare_detects_regression(self):
        baseline=self.root/'base'; candidate=self.root/'candidate'; baseline.mkdir(); candidate.mkdir(); (baseline/'a.kt').write_text('old'); (candidate/'a.kt').write_text('new'); (candidate/'b.kt').write_text('new')
        r=mod.compare(baseline,candidate); self.assertEqual(r['counts'],{'added':1,'removed':0,'changed':1}); self.assertEqual(r['status'],'REGRESSION_CANDIDATE')
    def test_rollback_restores_to_new_candidate_and_verifies(self):
        out=mod.snapshot(self.source,self.root/'snapshots','known-good',True); restored=mod.rollback(self.root/'snapshots'/out['id'],self.root/'restored'); self.assertTrue(restored['requiresReview']); self.assertFalse(restored['deployed']); self.assertEqual((self.root/'restored'/'Main.kt').read_text(),'one\n')
    def test_rollback_refuses_tampered_snapshot(self):
        out=mod.snapshot(self.source,self.root/'snapshots','baseline'); p=self.root/'snapshots'/out['id']; (p/'Main.kt').write_text('tampered')
        with self.assertRaisesRegex(ValueError,'integrity'): mod.rollback(p,self.root/'restored')
    def test_overlapping_snapshot_paths_rejected(self):
        with self.assertRaisesRegex(ValueError,'overlap'): mod.copy_tree(self.source,self.source/'inside')

class SupervisorTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.db=Path(self.temp.name)/'control.sqlite3'; os.environ['VIBES_WORKSPACE_ADMIN_TOKEN']='expected'; os.environ.pop('VIBES_WORKSPACE_ADMIN_TOKEN_PROVIDED',None)
    def tearDown(self): self.temp.cleanup(); os.environ.pop('VIBES_WORKSPACE_ADMIN_TOKEN',None); os.environ.pop('VIBES_WORKSPACE_ADMIN_TOKEN_PROVIDED',None)
    def args(self,**kwargs):
        from types import SimpleNamespace
        defaults=dict(interval=60,max_cycles=2,failure_threshold=2,budget_usd=1.0,max_seconds=60,success=False,cost_usd=0.0,duration_seconds=0)
        defaults.update(kwargs); return SimpleNamespace(**defaults)
    def test_mutations_require_admin_token(self):
        with self.assertRaises(PermissionError): mod.supervisor(self.db,'start',self.args())
    def test_emergency_stop_is_persistent_and_requires_clear(self):
        os.environ['VIBES_WORKSPACE_ADMIN_TOKEN_PROVIDED']='expected'; mod.supervisor(self.db,'start',self.args()); state=mod.supervisor(self.db,'emergency-stop',self.args()); self.assertTrue(state['emergencyStop']); self.assertFalse(state['canRun'])
        with self.assertRaisesRegex(ValueError,'Emergency stop'): mod.supervisor(self.db,'start',self.args())
        mod.supervisor(self.db,'clear-emergency',self.args()); self.assertFalse(mod.supervisor(self.db,'status',self.args())['enabled'])
    def test_failure_threshold_disables_continuous(self):
        os.environ['VIBES_WORKSPACE_ADMIN_TOKEN_PROVIDED']='expected'; mod.supervisor(self.db,'configure',self.args(failure_threshold=2)); mod.supervisor(self.db,'start',self.args())
        mod.supervisor(self.db,'record',self.args(success=False)); state=mod.supervisor(self.db,'record',self.args(success=False)); self.assertFalse(state['enabled']); self.assertEqual(state['consecutiveFailures'],2); self.assertEqual(state['alerts'][-1]['code'],'FAILURE_THRESHOLD')
    def test_budget_limit_disables_continuous(self):
        os.environ['VIBES_WORKSPACE_ADMIN_TOKEN_PROVIDED']='expected'; mod.supervisor(self.db,'configure',self.args(budget_usd=.2)); mod.supervisor(self.db,'start',self.args()); state=mod.supervisor(self.db,'record',self.args(success=True,cost_usd=.2)); self.assertFalse(state['enabled']); self.assertAlmostEqual(state['spentUsd'],.2); self.assertEqual(state['alerts'][-1]['code'],'BUDGET_LIMIT')
    def test_unsafe_limits_rejected(self):
        os.environ['VIBES_WORKSPACE_ADMIN_TOKEN_PROVIDED']='expected'
        with self.assertRaisesRegex(ValueError,'Unsafe supervisor limits'): mod.supervisor(self.db,'configure',self.args(interval=1))

if __name__=='__main__': unittest.main()
