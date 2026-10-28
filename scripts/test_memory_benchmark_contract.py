"""Source-contract tests for the Kotlin feature wiring (not a substitute for Kotlin compilation)."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "src" / "main" / "kotlin" / "com" / "vibes" / "quantum" / "server"
# The Android client is a separate sibling project and is not part of this bundle.
ANDROID_CLIENT = ROOT.parent / "Vyron_User_App" / "java" / "com" / "vibes" / "only" / "VibesEngineClient.kt"


class UnifiedMemoryContractTests(unittest.TestCase):
    def test_unified_memory_is_registered_in_server_routing(self):
        routing = (SERVER / "Routing.kt").read_text(encoding="utf-8")
        self.assertIn("UnifiedMemoryIndex(Paths.get(unifiedMemoryPath))", routing)
        self.assertIn("unifiedMemoryRoutes(adminKey, mutationLimiter, unifiedMemoryIndex)", routing)
        self.assertIn("VIBES_UNIFIED_MEMORY_PATH", routing)

    def test_memory_routes_require_user_session_and_explicit_consent(self):
        routes = (SERVER / "memory" / "UnifiedMemoryRoutes.kt").read_text(encoding="utf-8")
        self.assertIn("resolveUserId(token)", routes)
        self.assertIn("HttpStatusCode.Unauthorized", routes)
        self.assertIn("if (!request.consent)", routes)
        self.assertIn("ownerScope(owner)", routes)
        self.assertIn("forgetScope(ownerScope(owner))", routes)

    def test_memory_index_encrypts_records_with_authenticated_encryption(self):
        index = (SERVER / "memory" / "UnifiedMemoryIndex.kt").read_text(encoding="utf-8")
        self.assertIn('Cipher.getInstance("AES/GCM/NoPadding")', index)
        self.assertIn("cipher.updateAAD(aad)", index)
        self.assertIn("VIBES_UNIFIED_MEMORY_KEY_HEX", index)
        self.assertIn("contentHash", index)
        self.assertIn("sourceReference", index)
        self.assertIn("forgetScope", index)

    @unittest.skipUnless(ANDROID_CLIENT.exists(), "Android client project (Vyron_User_App) is not in this bundle")
    def test_android_client_exposes_scoped_memory_operations(self):
        client = ANDROID_CLIENT.read_text(encoding="utf-8")
        for method in ("saveUnifiedMemory", "searchUnifiedMemory", "listUnifiedMemory", "deleteUnifiedMemory", "deleteAllUnifiedMemory"):
            self.assertIn(f"fun {method}", client)
        self.assertIn("/api/v1/unified-memory/upsert", client)


class BenchmarkLabContractTests(unittest.TestCase):
    def test_kotlin_quality_gate_measures_candidate_and_persists_history(self):
        quality = (SERVER / "quality" / "QualityMemory.kt").read_text(encoding="utf-8")
        self.assertIn("stableSample(r.cases", quality)
        self.assertIn("categoryScores", quality)
        self.assertIn("criticalCaseFailures", quality)
        self.assertIn("private fun persist()", quality)
        self.assertIn("r.candidateScore.isFinite()", quality)
        self.assertIn("measured", quality)

    def test_quality_history_path_is_configurable_in_runtime(self):
        routing = (SERVER / "Routing.kt").read_text(encoding="utf-8")
        self.assertIn("VIBES_QUALITY_GATE_PATH", routing)
        self.assertIn("QualityGateService(stateFile = Paths.get(qualityGatePath))", routing)

    def test_benchmark_runner_does_not_include_answer_bodies_in_report(self):
        runner = (ROOT / "scripts" / "benchmark_lab.py").read_text(encoding="utf-8")
        self.assertIn("same deterministic, category-balanced sample", runner)
        self.assertIn("missing sampled answers", runner)
        self.assertIn("critical_failures", runner)
        self.assertNotIn('"answer": answer', runner)


if __name__ == "__main__":
    unittest.main()
