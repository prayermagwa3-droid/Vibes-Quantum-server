package com.vibes.quantum.server.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderRouterTest {
    @Test fun refusesUnpricedCloudProviderWhenRequestHasABudget() = runBlocking {
        var calls = 0
        val adapter = ProviderAdapter { _, _, _, _ -> calls++; ProviderPayload("should not run") }
        val provider = AiProviderConfig("cloud", "https://provider.example/v1", "configured-model", "server-secret", false, setOf("chat"))
        val router = ProviderRouter(listOf(provider), adapter)
        val result = router.complete("system", "user prompt", 100, CostBudget(0.50), onlineAllowed = true)
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test fun cloudRequestIsSkippedWhenEstimatedCostExceedsBudget() = runBlocking {
        var calls = 0
        val provider = AiProviderConfig("cloud", "https://provider.example/v1", "configured-model", "server-secret", false, setOf("chat"), inputCostPer1kUsd = 0.10, outputCostPer1kUsd = 0.10)
        val router = ProviderRouter(listOf(provider), ProviderAdapter { _, _, _, _ -> calls++; ProviderPayload("must not run") })
        val response = router.complete("system", "question", 500, CostBudget(0.001), onlineAllowed = true)
        assertNull(response)
        assertEquals(0, calls)
    }

    @Test fun credentialLikeTextIsRedactedBeforeCloudAdapter() = runBlocking {
        var outbound = ""
        val adapter = ProviderAdapter { _, system, user, _ -> outbound = "$system\n$user"; ProviderPayload("ok", 10, 1) }
        val provider = AiProviderConfig("cloud", "https://provider.example/v1", "configured-model", "server-secret", false, setOf("chat"), inputCostPer1kUsd = 0.01, outputCostPer1kUsd = 0.02)
        val router = ProviderRouter(listOf(provider), adapter)
        val result = router.complete("Please help", "password=hunter2 and token sk-abcdefghijklmnopqrstuvwxyz", 20, CostBudget(1.0), onlineAllowed = true)
        assertNotNull(result)
        assertFalse(outbound.contains("hunter2"))
        assertFalse(outbound.contains("sk-abcdefghijklmnopqrstuvwxyz"))
        assertTrue(outbound.contains("REDACTED"))
    }

    @Test fun providerTimeoutIsHandledAsFailureRatherThanCrashingCouncilCaller() = runBlocking {
        val slow = AiProviderConfig("slow", "https://provider.example/v1", "configured-model", "server-secret", false, setOf("chat"), timeoutMillis = 500, inputCostPer1kUsd = 0.01, outputCostPer1kUsd = 0.02)
        val router = ProviderRouter(listOf(slow), ProviderAdapter { _, _, _, _ -> delay(3_000); ProviderPayload("late") })
        val response = withTimeout(2_000) { router.complete("system", "question", 50, CostBudget(1.0), onlineAllowed = true) }
        assertNull(response)
    }

    @Test fun callerCancellationPropagatesAndDoesNotReturnAFakeAnswer() = runBlocking {
        val provider = AiProviderConfig("slow", "https://provider.example/v1", "configured-model", "server-secret", false, setOf("chat"), timeoutMillis = 10_000, inputCostPer1kUsd = 0.01, outputCostPer1kUsd = 0.02)
        val router = ProviderRouter(listOf(provider), ProviderAdapter { _, _, _, _ -> delay(5_000); ProviderPayload("late") })
        val job = launch { router.complete("system", "question", 50, CostBudget(1.0), onlineAllowed = true) }
        delay(50)
        job.cancel(CancellationException("test cancellation"))
        job.join()
        assertTrue(job.isCancelled)
    }
}
