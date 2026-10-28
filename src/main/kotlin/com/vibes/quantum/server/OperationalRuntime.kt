package com.vibes.quantum.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.math.ceil

@Serializable
data class OperationalLimitConfig(
    val providerTimeoutMs: Long = 12_000,
    val maxConcurrentProviderCalls: Int = 4,
    val maxProviderRetries: Int = 0,
    val retryBackoffBaseMs: Long = 250,
    val perApiKeyRequestsPerMinute: Int = 20,
    val perAppRequestsPerMinute: Int = 120,
    val requestProviderCallBudget: Int = 1,
    val requestNetworkCallBudget: Int = 4,
    val requestElapsedBudgetMs: Long = 30_000,
    val requestTokenBudget: Int = 4_096,
    val circuitFailureThreshold: Int = 5,
    val circuitOpenMs: Long = 30_000
) {
    fun validate() {
        require(providerTimeoutMs in 250L..120_000L) { "VIBES_PROVIDER_TIMEOUT_MS must be 250..120000" }
        require(maxConcurrentProviderCalls in 1..1024) { "VIBES_MAX_CONCURRENT_PROVIDER_CALLS must be 1..1024" }
        require(maxProviderRetries in 0..5) { "VIBES_PROVIDER_MAX_RETRIES must be 0..5" }
        require(retryBackoffBaseMs in 0L..30_000L) { "VIBES_PROVIDER_RETRY_BACKOFF_MS must be 0..30000" }
        require(perApiKeyRequestsPerMinute in 1..100_000) { "VIBES_CHAT_LIMIT_PER_API_KEY_PER_MINUTE must be 1..100000" }
        require(perAppRequestsPerMinute in 1..1_000_000) { "VIBES_CHAT_LIMIT_PER_APP_PER_MINUTE must be 1..1000000" }
        require(requestProviderCallBudget in 1..20) { "VIBES_REQUEST_PROVIDER_CALL_BUDGET must be 1..20" }
        require(requestNetworkCallBudget in 1..100) { "VIBES_REQUEST_NETWORK_CALL_BUDGET must be 1..100" }
        require(requestElapsedBudgetMs in 250L..600_000L) { "VIBES_REQUEST_ELAPSED_BUDGET_MS must be 250..600000" }
        require(requestTokenBudget in 1..128_000) { "VIBES_REQUEST_TOKEN_BUDGET must be 1..128000" }
        require(circuitFailureThreshold in 1..100) { "VIBES_CIRCUIT_FAILURE_THRESHOLD must be 1..100" }
        require(circuitOpenMs in 1_000L..600_000L) { "VIBES_CIRCUIT_OPEN_MS must be 1000..600000" }
    }

    companion object {
        private fun intEnv(name: String, fallback: Int): Int = System.getenv(name)?.trim()?.toIntOrNull() ?: fallback
        private fun longEnv(name: String, fallback: Long): Long = System.getenv(name)?.trim()?.toLongOrNull() ?: fallback
        fun fromEnvironment(): OperationalLimitConfig = OperationalLimitConfig(
            providerTimeoutMs = longEnv("VIBES_PROVIDER_TIMEOUT_MS", 12_000),
            maxConcurrentProviderCalls = intEnv("VIBES_MAX_CONCURRENT_PROVIDER_CALLS", 4),
            maxProviderRetries = intEnv("VIBES_PROVIDER_MAX_RETRIES", 0),
            retryBackoffBaseMs = longEnv("VIBES_PROVIDER_RETRY_BACKOFF_MS", 250),
            perApiKeyRequestsPerMinute = intEnv("VIBES_CHAT_LIMIT_PER_API_KEY_PER_MINUTE", 20),
            perAppRequestsPerMinute = intEnv("VIBES_CHAT_LIMIT_PER_APP_PER_MINUTE", 120),
            requestProviderCallBudget = intEnv("VIBES_REQUEST_PROVIDER_CALL_BUDGET", 1),
            requestNetworkCallBudget = intEnv("VIBES_REQUEST_NETWORK_CALL_BUDGET", 4),
            requestElapsedBudgetMs = longEnv("VIBES_REQUEST_ELAPSED_BUDGET_MS", 30_000),
            requestTokenBudget = intEnv("VIBES_REQUEST_TOKEN_BUDGET", 4_096),
            circuitFailureThreshold = intEnv("VIBES_CIRCUIT_FAILURE_THRESHOLD", 5),
            circuitOpenMs = longEnv("VIBES_CIRCUIT_OPEN_MS", 30_000)
        ).also { it.validate() }
    }
}

@Serializable
data class OpsMetricValue(
    val value: Double? = null,
    val unit: String,
    val status: String,
    val source: String,
    val note: String? = null
)

@Serializable
data class RouteLatencyMetric(
    val method: String,
    val route: String,
    val count: Long,
    val successes: Long,
    val errors: Long,
    val errorRate: Double,
    val avgLatencyMs: Double,
    val maxLatencyMs: Double,
    val p95LatencyMs: Double
)

@Serializable
data class ProviderLatencyMetric(
    val calls: Long,
    val successes: Long,
    val errors: Long,
    val retries: Long,
    val avgLatencyMs: Double?,
    val p95LatencyMs: Double?,
    val lastStatusCode: Int?,
    val circuitState: String
)

@Serializable
data class OperationalSnapshot(
    val generatedAt: String,
    val correlationIdsEnabled: Boolean,
    val limits: OperationalLimitConfig,
    val killSwitches: Map<String, Boolean>,
    val httpRequests: List<RouteLatencyMetric>,
    val provider: ProviderLatencyMetric,
    val resources: Map<String, OpsMetricValue>,
    val operationalNotes: List<String>,
    val alerts: List<String> = emptyList()
)

@Serializable
data class PersistedOperationalSwitches(
    val providerRoutingEnabled: Boolean = true,
    val backgroundLearningEnabled: Boolean = false,
    val updatedAt: String = Instant.now().toString()
)

@Serializable
data class KillSwitchUpdateRequest(
    val providerRoutingEnabled: Boolean? = null,
    val backgroundLearningEnabled: Boolean? = null
)

@Serializable
data class KillSwitchUpdateResponse(
    val providerRoutingEnabled: Boolean,
    val backgroundLearningEnabled: Boolean,
    val updatedAt: String,
    val note: String = "Switches are persisted server-side. Disabling routing blocks new provider calls; it does not terminate a request already in flight."
)

@Serializable
data class OpsStatusResponse(
    val enabled: Boolean,
    val note: String = "Admin-only operational visibility. Request bodies, prompts, response text, credentials and user identifiers are not recorded."
)

/**
 * Gateway-local operational plane. Metrics deliberately use normalized route templates and no content labels.
 * Values that cannot be measured in this runtime are returned as unavailable rather than guessed.
 */
class OperationalRuntime(
    private val stateFile: String = System.getenv("VIBES_OPS_SWITCH_FILE")?.takeIf { it.isNotBlank() } ?: "data/vibes-ops-switches.json",
    private val dataDirectory: String = System.getenv("VIBES_DATA_DIRECTORY")?.takeIf { it.isNotBlank() } ?: "data",
    val limits: OperationalLimitConfig = OperationalLimitConfig.fromEnvironment()
) {
    private data class RouteBucket(
        var count: Long = 0,
        var successes: Long = 0,
        var errors: Long = 0,
        var totalLatencyMs: Double = 0.0,
        var maxLatencyMs: Double = 0.0,
        val recentSamples: ArrayDeque<Double> = ArrayDeque()
    )

    private val routeMetrics = ConcurrentHashMap<String, RouteBucket>()
    private val providerSamples = ArrayDeque<Double>()
    private val providerLock = Any()
    private var providerCalls = 0L
    private var providerSuccesses = 0L
    private var providerErrors = 0L
    private var providerRetries = 0L
    private var providerTotalLatency = 0.0
    private var providerLastStatus: Int? = null
    private var promptTokensMeasured = 0L
    private var completionTokensMeasured = 0L
    private var tokenUsageObservations = 0L
    private var estimatedCostUsd = 0.0
    private var estimatedCostObservations = 0L
    private val inputCostPer1k = System.getenv("VIBES_UPSTREAM_INPUT_COST_PER_1K_USD")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    private val outputCostPer1k = System.getenv("VIBES_UPSTREAM_OUTPUT_COST_PER_1K_USD")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    private val providerSlots = Semaphore(limits.maxConcurrentProviderCalls, true)
    private var consecutiveProviderFailures = 0
    private var circuitOpenedAtMillis: Long? = null
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }
    private val statePath: Path = Paths.get(stateFile).toAbsolutePath().normalize()
    private val switchesLock = Any()
    @Volatile private var providerRoutingEnabled = true
    @Volatile private var backgroundLearningEnabled = false
    @Volatile private var switchesUpdatedAt = Instant.now().toString()

    init {
        limits.validate()
        loadSwitches()
    }

    fun correlationId(incoming: String?): String = incoming?.takeIf {
        it.length in 1..64 && it.all { ch -> ch.isLetterOrDigit() || ch in "._-" }
    } ?: "vq_" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)

    fun normalizedRoute(path: String): String = when {
        path == "/v1/chat/completions" || path.startsWith("/v1/chat/completions/") -> "/v1/chat/completions"
        path == "/api/health" || path == "/api/ready" || path == "/readyz" -> path
        path.startsWith("/api/v1/apps/") -> "/api/v1/apps/{id}"
        path == "/api/v1/apps" -> path
        path.startsWith("/api/v1/research/runs/") -> "/api/v1/research/runs/{id}"
        path.startsWith("/api/v1/research/") -> "/api/v1/research/*"
        path.startsWith("/api/v1/learning/") -> "/api/v1/learning/*"
        path.startsWith("/api/v1/ops/") -> "/api/v1/ops/*"
        path.startsWith("/api/v1/") -> path.split('/').take(4).joinToString("/").ifBlank { "/api/v1/*" }
        path == "/" -> "/"
        else -> "/other"
    }

    fun recordHttp(method: String, path: String, statusCode: Int, elapsedMs: Double) {
        val route = normalizedRoute(path)
        val key = "${method.uppercase().take(10)} $route"
        routeMetrics.computeIfAbsent(key) { RouteBucket() }.let { bucket ->
            synchronized(bucket) {
                bucket.count++
                if (statusCode in 200..399) bucket.successes++ else bucket.errors++
                bucket.totalLatencyMs += elapsedMs.coerceAtLeast(0.0)
                bucket.maxLatencyMs = maxOf(bucket.maxLatencyMs, elapsedMs.coerceAtLeast(0.0))
                bucket.recentSamples.addLast(elapsedMs.coerceAtLeast(0.0))
                while (bucket.recentSamples.size > 512) bucket.recentSamples.removeFirst()
            }
        }
    }

    fun mayRouteProvider(): Boolean = providerRoutingEnabled && !isCircuitOpen()
    fun providerRoutingEnabled(): Boolean = providerRoutingEnabled
    fun backgroundLearningEnabled(): Boolean = backgroundLearningEnabled
    fun tryAcquireProviderSlot(): Boolean = providerSlots.tryAcquire()
    fun releaseProviderSlot() = providerSlots.release()
    fun providerQueueDepth(): Int = (limits.maxConcurrentProviderCalls - providerSlots.availablePermits()).coerceAtLeast(0)

    @Synchronized
    fun recordProviderUsage(promptTokens: Long, completionTokens: Long) {
        val input = promptTokens.coerceAtLeast(0L)
        val output = completionTokens.coerceAtLeast(0L)
        promptTokensMeasured += input
        completionTokensMeasured += output
        tokenUsageObservations++
        if (inputCostPer1k != null && outputCostPer1k != null) {
            estimatedCostUsd += (input / 1_000.0 * inputCostPer1k) + (output / 1_000.0 * outputCostPer1k)
            estimatedCostObservations++
        }
    }

    @Synchronized
    fun recordProvider(latencyMs: Double, statusCode: Int, success: Boolean, retries: Int = 0) {
        providerCalls++
        providerRetries += retries.coerceAtLeast(0)
        providerLastStatus = statusCode
        providerTotalLatency += latencyMs.coerceAtLeast(0.0)
        synchronized(providerLock) {
            providerSamples.addLast(latencyMs.coerceAtLeast(0.0))
            while (providerSamples.size > 512) providerSamples.removeFirst()
        }
        if (success) {
            providerSuccesses++
            consecutiveProviderFailures = 0
            circuitOpenedAtMillis = null
        } else {
            providerErrors++
            consecutiveProviderFailures++
            if (consecutiveProviderFailures >= limits.circuitFailureThreshold && circuitOpenedAtMillis == null) circuitOpenedAtMillis = System.currentTimeMillis()
        }
    }

    private fun isCircuitOpen(): Boolean = synchronized(this) {
        val opened = circuitOpenedAtMillis ?: return@synchronized false
        if (System.currentTimeMillis() - opened >= limits.circuitOpenMs) {
            circuitOpenedAtMillis = null
            consecutiveProviderFailures = 0
            false
        } else true
    }

    @Synchronized
    fun updateSwitches(update: KillSwitchUpdateRequest): KillSwitchUpdateResponse {
        val newProviderRouting = update.providerRoutingEnabled ?: providerRoutingEnabled
        val newBackgroundLearning = update.backgroundLearningEnabled ?: backgroundLearningEnabled
        val now = Instant.now().toString()
        val contents = json.encodeToString(PersistedOperationalSwitches(newProviderRouting, newBackgroundLearning, now))
        val parent = statePath.parent ?: throw IllegalStateException("Operational state directory is unavailable")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "vibes-ops-", ".tmp")
        try {
            Files.writeString(temporary, contents)
            try { Files.move(temporary, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING) }
            runCatching { Files.setPosixFilePermissions(statePath, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        } finally { Files.deleteIfExists(temporary) }
        providerRoutingEnabled = newProviderRouting
        backgroundLearningEnabled = newBackgroundLearning
        switchesUpdatedAt = now
        return KillSwitchUpdateResponse(newProviderRouting, newBackgroundLearning, now)
    }

    private fun loadSwitches() {
        if (!Files.exists(statePath)) {
            providerRoutingEnabled = System.getenv("VIBES_PROVIDER_ROUTING_ENABLED")?.equals("false", true) != true
            backgroundLearningEnabled = System.getenv("VIBES_BACKGROUND_LEARNING_ENABLED")?.equals("true", true) == true
            switchesUpdatedAt = Instant.now().toString()
            return
        }
        try {
            val stored = json.decodeFromString<PersistedOperationalSwitches>(Files.readString(statePath))
            providerRoutingEnabled = stored.providerRoutingEnabled
            backgroundLearningEnabled = stored.backgroundLearningEnabled
            switchesUpdatedAt = stored.updatedAt
        } catch (failure: Exception) {
            throw IllegalStateException("Could not load operational switch state. Back up and repair it before restarting.", failure)
        }
    }

    fun snapshot(): OperationalSnapshot {
        val routes = routeMetrics.entries.mapNotNull { (key, bucket) ->
            val pieces = key.split(" ", limit = 2)
            synchronized(bucket) {
                if (pieces.size != 2 || bucket.count == 0L) null else {
                    val sorted = bucket.recentSamples.sorted()
                    val p95Index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, (sorted.size - 1).coerceAtLeast(0))
                    RouteLatencyMetric(pieces[0], pieces[1], bucket.count, bucket.successes, bucket.errors,
                        bucket.errors.toDouble() / bucket.count, bucket.totalLatencyMs / bucket.count, bucket.maxLatencyMs,
                        sorted.getOrElse(p95Index) { 0.0 })
                }
            }
        }.sortedByDescending { it.count }.take(40)
        val providerSampleSnapshot = synchronized(providerLock) { providerSamples.toList().sorted() }
        val p95Index = (ceil(providerSampleSnapshot.size * 0.95).toInt() - 1).coerceIn(0, (providerSampleSnapshot.size - 1).coerceAtLeast(0))
        val heap = Runtime.getRuntime()
        val usedHeap = (heap.totalMemory() - heap.freeMemory()).coerceAtLeast(0L)
        val storage = storageBytes(Paths.get(dataDirectory))
        val queue = providerQueueDepth()
        val circuitState = if (isCircuitOpen()) "open" else "closed"
        val alerts = mutableListOf<String>()
        if (circuitState == "open") alerts += "Provider circuit breaker is open; new direct gateway provider calls are being rejected."
        if (!providerRoutingEnabled) alerts += "Provider routing kill switch is OFF; new provider requests are blocked."
        if (!backgroundLearningEnabled) alerts += "Background/source learning switch is OFF; new source-learning runs are blocked."
        if (providerCalls >= 10 && providerErrors.toDouble() / providerCalls >= 0.25) alerts += "Provider error rate is at least 25% over the process lifetime; inspect provider health and timeouts."
        if (heap.maxMemory() > 0 && usedHeap.toDouble() / heap.maxMemory() >= 0.90) alerts += "JVM heap usage has reached at least 90% of the configured maximum."
        return OperationalSnapshot(
            generatedAt = Instant.now().toString(), correlationIdsEnabled = true, limits = limits,
            killSwitches = mapOf("providerRoutingEnabled" to providerRoutingEnabled, "backgroundLearningEnabled" to backgroundLearningEnabled),
            httpRequests = routes,
            provider = ProviderLatencyMetric(providerCalls, providerSuccesses, providerErrors, providerRetries,
                if (providerCalls > 0) providerTotalLatency / providerCalls else null,
                providerSampleSnapshot.getOrNull(p95Index).takeIf { providerSampleSnapshot.isNotEmpty() }, providerLastStatus,
                circuitState),
            resources = mapOf(
                "heap_used" to OpsMetricValue(usedHeap.toDouble(), "bytes", "measured", "JVM Runtime.totalMemory - freeMemory"),
                "heap_committed" to OpsMetricValue(heap.totalMemory().toDouble(), "bytes", "measured", "JVM Runtime.totalMemory"),
                "heap_max" to OpsMetricValue(if (heap.maxMemory() > 0) heap.maxMemory().toDouble() else null, "bytes", if (heap.maxMemory() > 0) "measured" else "unavailable", "JVM Runtime.maxMemory"),
                "persistent_storage" to storage,
                "provider_in_flight" to OpsMetricValue(queue.toDouble(), "calls", "measured", "gateway semaphore", "Concurrent requests holding a provider slot, including response streaming"),
                "provider_prompt_tokens" to OpsMetricValue(if (tokenUsageObservations > 0) promptTokensMeasured.toDouble() else null, "tokens", if (tokenUsageObservations > 0) "measured" else "unavailable", "provider usage object", "Aggregated only where the upstream returns token usage; current-process counter"),
                "provider_completion_tokens" to OpsMetricValue(if (tokenUsageObservations > 0) completionTokensMeasured.toDouble() else null, "tokens", if (tokenUsageObservations > 0) "measured" else "unavailable", "provider usage object", "Aggregated only where the upstream returns token usage; current-process counter"),
                "estimated_cost_usd" to OpsMetricValue(if (estimatedCostObservations > 0) estimatedCostUsd else null, "USD", if (estimatedCostObservations > 0) "estimated" else "unavailable", "token usage multiplied by configured VIBES_UPSTREAM_*_COST_PER_1K_USD", "Estimated from configured per-1K rates; no provider billing API is queried"),
                "cache_hit_rate" to OpsMetricValue(null, "ratio", "unavailable", "not instrumented", "No cache is configured in this gateway path"),
                "database_latency" to OpsMetricValue(null, "ms", "unavailable", "not instrumented", "Gateway JSON stores are file-backed; there is no database driver in this service"),
                "job_queue_depth" to OpsMetricValue(null, "jobs", "unavailable", "not instrumented", "No shared persistent job queue is configured"),
                "downloaded_bytes" to OpsMetricValue(null, "bytes", "unavailable", "not instrumented", "Per-source-run values are available from the research run record"),
                "cost_usd" to OpsMetricValue(null, "USD", "unavailable", "provider billing API not connected", "Token usage is measured when providers return it; dollar cost is not inferred")
            ),
            operationalNotes = listOf(
                "HTTP latency is measured from gateway ingress to completion; p95 uses the last 512 samples per normalized route.",
                "Provider latency and retries are measured for calls made through the instrumented provider path. Retry defaults to 0 to avoid duplicate paid POSTs.",
                "Prompts, response text, request bodies, API keys, authorization headers and user IDs are never telemetry labels.",
                "Kill switches block new work. They do not cancel requests already in flight.",
                "Kill switches updated at $switchesUpdatedAt"
            ),
            alerts = alerts
        )
    }

    private fun storageBytes(root: Path): OpsMetricValue {
        if (!Files.exists(root)) return OpsMetricValue(0.0, "bytes", "measured", "filesystem walk", "Configured data directory does not exist yet")
        return try {
            val stream = Files.walk(root)
            val bytes = try { stream.filter { Files.isRegularFile(it) }.mapToLong { path -> runCatching { Files.size(path) }.getOrDefault(0L) }.sum() } finally { stream.close() }
            OpsMetricValue(bytes.toDouble(), "bytes", "measured", "filesystem walk", "Calculated for the configured data directory only")
        } catch (_: Exception) {
            OpsMetricValue(null, "bytes", "unavailable", "filesystem walk", "Data-directory storage could not be measured")
        }
    }
}
