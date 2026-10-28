"""Source-contract tests for the Kotlin Self-Improving Brain wiring.

These verify that core integration contracts remain connected; they do not replace
Kotlin/Ktor compilation or a live server integration test.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "src/main/kotlin/com/vibes/quantum/server/agents/WorkspaceEngineeringService.kt"
ROUTES = ROOT / "src/main/kotlin/com/vibes/quantum/server/agents/WorkspaceEngineeringRoutes.kt"
ENGINE = ROOT.parent / "Vibes_Quantum_Engine/java/com/vibes/only"
CLIENT = ENGINE / "workspace/WorkspaceEngineeringClient.kt"
VIEW_MODEL = ENGINE / "MainViewModel.kt"
UI = ENGINE / "ui/VibesApp.kt"


class WorkspaceBrainContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.service = SERVICE.read_text(encoding="utf-8")
        cls.routes = ROUTES.read_text(encoding="utf-8")
        # Android engine sources are a sibling project; they are optional in this bundle.
        engine_present = ENGINE.exists()
        cls.client = CLIENT.read_text(encoding="utf-8") if engine_present else ""
        cls.vm = VIEW_MODEL.read_text(encoding="utf-8") if engine_present else ""
        cls.ui = UI.read_text(encoding="utf-8") if engine_present else ""

    def test_memory_is_scoped_by_app_and_target_digest(self):
        self.assertIn('digest((appId + "\\u0000" + targetApp.trim().lowercase())', self.service)
        self.assertIn('root.resolve("brain")', self.service)
        self.assertIn('MAX_BRAIN_LESSONS = 40', self.service)

    def test_brain_lessons_feed_future_agent_prompts(self):
        self.assertGreaterEqual(self.service.count("brainContext("), 5)
        self.assertIn("SELF-IMPROVEMENT BRAIN LESSONS", self.service)
        self.assertIn("BRAIN LESSONS", self.service)

    def test_verified_success_requires_passing_tests_and_reviewer(self):
        self.assertIn('candidate.status == "TESTS_PASSED"', self.service)
        self.assertIn('Regex("(?im)^\\\\s*VERDICT:\\\\s*(PASS|RETRY|BLOCK)', self.service)
        self.assertIn('verdict(evaluation) == "PASS"', self.service)
        self.assertIn('all { it.status == "PASSED" }', self.service)
        self.assertIn('recordBrainOutcome(live, "PASS"', self.service)
        self.assertIn('recordBrainOutcome(live, "BLOCK"', self.service)
        self.assertIn('recordBrainOutcome(live, "RETRY"', self.service)

    def test_brain_endpoints_require_workspace_scope(self):
        self.assertIn('/api/v1/workspace/brain', self.routes)
        self.assertIn('post("/reflect")', self.routes)
        self.assertGreaterEqual(self.routes.count('"workspace:run" !in access.scopes'), 2)

    @unittest.skipUnless(ENGINE.exists(), "Android engine project (Vibes_Quantum_Engine) is not in this bundle")
    def test_android_brain_controls_are_wired_to_client_and_state(self):
        self.assertIn('"/api/v1/workspace/brain/reflect"', self.client)
        self.assertIn('engineeringWorkspaceClient.reflectBrain(target)', self.vm)
        self.assertIn('Text("Reflect & plan")', self.ui)
        self.assertIn('Text("Refresh brain")', self.ui)
        self.assertIn('Text("Use brain suggestions as next objective")', self.ui)
        self.assertIn('Text("Reliability")', self.ui)
        self.assertIn('Text("Tests")', self.ui)
        self.assertIn('Text("Simplify")', self.ui)


    def test_swarm_has_all_specialists_and_integration_lead(self):
        for agent_id in ("research", "architecture", "coding", "testing", "security", "performance", "integration"):
            self.assertIn(f'WorkspaceAgentReport("{agent_id}"', self.service)
        self.assertIn('awaitAll(testReview, securityReview, performanceReview)', self.service)
        self.assertIn('You are the integration lead', self.service)

    @unittest.skipUnless(ENGINE.exists(), "Android engine project (Vibes_Quantum_Engine) is not in this bundle")
    def test_builder_profile_and_artifact_bundle_are_exposed_end_to_end(self):
        self.assertIn('projectProfile = detectedProfile', self.service)
        self.assertIn('artifactZipReady', self.service)
        self.assertIn('"audit", "--baseline", sourceProjectPath.toString()', self.service)
        self.assertIn('candidate-audit.json', self.service)
        self.assertIn('candidate-review.diff.txt', self.service)
        self.assertIn('release-dossier.txt', self.service)
        self.assertIn('engineeringAudit', self.client)
        self.assertIn('get("/{jobId}/artifacts.zip")', self.routes)
        self.assertIn('enqueueBuildArtifactsDownload', self.client)
        self.assertIn('Download logs, diff & review dossier', self.ui)
        self.assertIn('Detected project profile', self.ui)
        inspector = (ENGINE / "upgrade/ProjectArchiveInspector.kt").read_text(encoding="utf-8")
        self.assertIn("extension in SOURCE_EXTENSIONS", inspector)
        self.assertIn("MAX_SINGLE_SOURCE_BYTES", inspector)
        self.assertIn("ZipOutputStream", inspector)
        self.assertIn("looksLikeCredentialFile", inspector)
        self.assertIn("google-services.json", inspector)
        self.assertIn('sourceZipPicker.launch(arrayOf("*/*"))', self.ui)
        self.assertIn("single file is analysis-only", self.ui)

    @unittest.skipUnless(ENGINE.exists(), "Android engine project (Vibes_Quantum_Engine) is not in this bundle")
    def test_engineering_panels_and_chatlab_actions_are_connected(self):
        for panel in ('Agent Swarm', 'App Builder', 'Build & Test', 'Run History', 'Automations', 'Projects', 'Connectors'):
            self.assertIn(f'"{panel}"', self.ui)
        self.assertIn('onGood = {', self.ui)
        self.assertIn('onBad = {', self.ui)
        self.assertIn('onTeach = {', self.ui)
        self.assertIn('onRegenerate = {', self.ui)
        self.assertIn('fun recordChatLabFeedback(', self.vm)
        self.assertIn('fun teachChatLabAnswer(', self.vm)



class SupervisorIntegrationContractTests(unittest.TestCase):
    def test_supervisor_is_admin_only_and_continuous_loop_fails_closed(self):
        root = Path(__file__).parents[1]
        routes = (root / "src/main/kotlin/com/vibes/quantum/server/agents/WorkspaceEngineeringRoutes.kt").read_text()
        service = (root / "src/main/kotlin/com/vibes/quantum/server/agents/WorkspaceEngineeringService.kt").read_text()
        control = (root / "scripts/workspace_control.py").read_text()
        self.assertIn('route("/api/v1/workspace/supervisor")', routes)
        self.assertIn('if (!access.gatewayAdmin)', routes)
        self.assertIn('if (record.continuous && !supervisorCanRun())', service)
        self.assertIn('recordSupervisorCycle(live, success = true)', service)
        self.assertIn('recordSupervisorCycle(live, success = false)', service)
        self.assertIn('VIBES_WORKSPACE_ADMIN_TOKEN_PROVIDED', control)
        self.assertIn('WorkspaceControlService().snapshot(baselinePath, versionsDir, "baseline"', service)
        self.assertIn('WorkspaceControlService().snapshot(projectPath, versionsDir, "known-good-cycle-$cycle"', service)
        self.assertIn('WorkspaceControlService().compare(sourceProjectPath, projectPath)', service)


if __name__ == "__main__":
    unittest.main()
