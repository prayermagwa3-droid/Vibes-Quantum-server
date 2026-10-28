package com.vibes.quantum.server.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CouncilServiceTest {
    private fun localRouter(adapter: ProviderAdapter = ProviderAdapter { _, _, _, _ -> ProviderPayload("Final answer using [S1].") }) =
        ProviderRouter(listOf(AiProviderConfig("local-a", "http://127.0.0.1:11434/v1", "configured-local-model", null, true,
            setOf("chat", "research", "code", "write", "memory", "voice", "plan"), maxInputTokens = 32_000, maxOutputTokens = 3_000)), adapter)

    @Test fun disagreementIsSurfacedAndDuplicateUrlsAreNotCountedAsIndependent() = runBlocking {
        val adapter = ProviderAdapter { _, system, _, _ ->
            when {
                system.contains("Logic member") -> ProviderPayload("Assessment: SUPPORTS\nKey finding: The conclusion follows.\nEvidence IDs: S1\nConcern: None")
                system.contains("Evidence/Truth member") -> ProviderPayload("Assessment: CHALLENGES\nKey finding: The evidence does not establish the claim.\nEvidence IDs: S1\nConcern: Missing independent support")
                system.contains("Critic member") -> ProviderPayload("Assessment: CHALLENGES\nKey finding: A counterexample may apply.\nEvidence IDs: S2\nConcern: Alternative explanation")
                system.contains("Synthesize one helpful") -> ProviderPayload("The source support is contested. [S1] Further verification is needed.")
                else -> ProviderPayload("Assessment: UNCERTAIN\nKey finding: Not sure.\nEvidence IDs: S1")
            }
        }
        val service = CouncilService(localRouter(adapter))
        val result = service.answer(CouncilRequest(
            prompt = "Does the candidate claim follow?", mode = "COUNCIL", maxCostUsd = 0.0,
            selectedMembers = listOf("logic", "evidence", "critic"), maxMembers = 3,
            evidence = listOf(
                CouncilEvidence("S1", "The report presents a statement.", "Report", "https://example.test/report?one=1", "Publisher A"),
                CouncilEvidence("S2", "A duplicate copy of the same report.", "Copy", "https://example.test/report?one=2", "Publisher A")
            ),
            candidates = listOf(CandidateAnswer("C1", "The statement is true", "provider-x", listOf("S1")))
        ))
        assertEquals("contested", result.status)
        assertTrue(result.disagreements.isNotEmpty())
        assertEquals("S1", result.evidence.first { it.id == "S2" }.duplicateOf)
        assertEquals("Low to moderate — member disagreement", result.confidence)
        assertFalse(result.answer.isBlank())
    }

    @Test fun trainerApprovedLocalClaimsAreIncludedAsProvenanceEvidence() = runBlocking {
        val approved = CouncilEvidence(
            id = "local-claim-approved-42",
            text = "Trainer-approved local claim: water has chemical formula H2O. Source references: https://example.test/chemistry",
            title = "Approved claim: water formula", url = "https://example.test/chemistry",
            publisher = "Vibes Quantum trainer-approved claim registry", retrievedAt = "2026-10-20T09:00:00Z"
        )
        val service = CouncilService(localRouter()) { _, limit -> assertTrue(limit > 0); listOf(approved) }
        val result = service.answer(CouncilRequest(prompt = "What is the chemical formula of water?", mode = "STANDARD", maxCostUsd = 0.0))
        assertTrue(result.evidence.any { it.id == "local-claim-approved-42" })
        assertTrue(result.progress.any { it.contains("trainer-approved local claim", ignoreCase = true) })
        assertFalse(result.unsupportedOrUnverified.any { it.contains("No source evidence was supplied") })
    }

    @Test fun normalizedClaimsFlagPossibleCrossProviderPolarityConflicts() = runBlocking {
        val providerConfigs = listOf("local-a", "local-b").map { id ->
            AiProviderConfig(id, "http://127.0.0.1:11434/v1", "test-model-$id", null, true, setOf("chat"), maxInputTokens = 32_000, maxOutputTokens = 3_000)
        }
        val adapter = ProviderAdapter { config, system, _, _ ->
            when {
                system.startsWith("Produce an initial concise candidate") -> ProviderPayload(
                    if (config.id == "local-a") "The treatment is safe for adults and has low risk according to current results." else "The treatment is not safe for adults and has low risk according to current results."
                )
                system.contains("Synthesize one helpful") -> ProviderPayload("The candidate answers differ about safety. Source review is required.")
                else -> ProviderPayload("Assessment: UNCERTAIN\nKey finding: The supplied evidence is not decisive.\nEvidence IDs: S1\nConcern: More source checking is needed")
            }
        }
        val service = CouncilService(ProviderRouter(providerConfigs, adapter))
        val result = service.answer(CouncilRequest(
            prompt = "Is the treatment safe?", mode = "COUNCIL", maxCostUsd = 0.0,
            selectedMembers = listOf("logic", "evidence"), maxMembers = 2,
            evidence = listOf(CouncilEvidence("S1", "The evidence has not independently established the treatment's safety."))
        ))
        assertEquals("contested", result.status)
        assertTrue(result.disagreements.any { it.contains("Possible candidate conflict") })
        assertTrue(result.normalizedClaims.size >= 2)
    }

    @Test fun allTwelveCouncilRolesAreDiscoverable() = runBlocking {
        val service = CouncilService(localRouter())
        val roles = service.roleDirectory()
        assertEquals(12, roles.size)
        assertTrue(roles.any { it.id == "historian" })
        assertTrue(roles.any { it.id == "future" && it.name == "Future" })
        assertTrue(roles.any { it.id == "vision" })
    }

    @Test fun localEvidenceSuppressesCloudProviderUnlessOnlineBoostIsEnabled() = runBlocking {
        var calls = 0
        val adapter = ProviderAdapter { _, _, _, _ -> calls++; ProviderPayload("Answer [S1]") }
        val onlineOnly = ProviderRouter(listOf(AiProviderConfig("cloud", "https://provider.example/v1", "model-from-config", "key", false, setOf("chat"), inputCostPer1kUsd = 0.01, outputCostPer1kUsd = 0.01)), adapter)
        val service = CouncilService(onlineOnly)
        service.answer(CouncilRequest(prompt = "Question", mode = "STANDARD", evidence = listOf(CouncilEvidence("S1", "User supplied evidence")), maxCostUsd = 0.5, onlineBoost = false))
        assertEquals(0, calls)
        service.answer(CouncilRequest(prompt = "Question", mode = "STANDARD", priorContext = listOf("private task context"), maxCostUsd = 0.5, onlineBoost = false))
        service.answer(CouncilRequest(prompt = "Compare candidates", mode = "STANDARD", candidates = listOf(CandidateAnswer("C1", "A private candidate to compare")), maxCostUsd = 0.5, onlineBoost = false))
        assertEquals(0, calls)
        service.answer(CouncilRequest(prompt = "Question", mode = "STANDARD", evidence = listOf(CouncilEvidence("S1", "User supplied evidence")), maxCostUsd = 0.5, onlineBoost = true))
        assertEquals(1, calls)
    }

    @Test fun disabledCouncilUsesStandardAnswerFlowAndNeverInventsAProviderResult() = runBlocking {
        val service = CouncilService(localRouter(ProviderAdapter { _, _, _, _ -> throw IllegalStateException("offline") }))
        val result = service.answer(CouncilRequest(prompt = "A question", enabled = false, mode = "COUNCIL", maxCostUsd = 0.0))
        assertEquals("DISABLED", result.mode)
        assertEquals("insufficient", result.status)
        assertTrue(result.answer.contains("could not reach", ignoreCase = true))
    }
}
