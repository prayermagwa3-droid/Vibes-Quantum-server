package com.vibes.quantum.server.sources

import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class SourceConnectorService(
    private val enabled: Boolean,
    connectors: List<SourceConnector>,
    private val store: SourceCandidateStore = SourceCandidateStore(),
    private val maxRequestsPerRun: Int = 8,
    private val maxBytesPerRun: Long = 512 * 1024,
    private val maxCandidatesPerRun: Int = 15,
    private val maxConcurrentRuns: Int = 1,
    private val clock: () -> Instant = { Instant.now() }
) {
    private val connectorsById = connectors.associateBy { it.id }
    private val busy = AtomicBoolean(false)
    private val connectorStats = ConcurrentHashMap<String, ConnectorStats>()
    @Volatile private var liveSession = LiveSourceLearningSession(updatedAt = clock().toString())

    fun liveLearningSession(): LiveSourceLearningSession = liveSession

    private fun updateLiveSession(
        runId: String? = liveSession.runId,
        status: String = liveSession.status,
        phase: String = liveSession.phase,
        currentConnectorId: String? = liveSession.currentConnectorId,
        startedAt: String? = liveSession.startedAt,
        requests: Int = liveSession.requests,
        downloadedBytes: Long = liveSession.downloadedBytes,
        candidatesCreated: Int = liveSession.candidatesCreated,
        checkpointStatus: String = liveSession.checkpointStatus,
        correlationId: String? = liveSession.correlationId
    ) {
        liveSession = LiveSourceLearningSession(
            runId = runId, status = status, phase = phase, currentConnectorId = currentConnectorId,
            startedAt = startedAt, updatedAt = clock().toString(), requests = requests,
            downloadedBytes = downloadedBytes, candidatesCreated = candidatesCreated,
            checkpointStatus = checkpointStatus, correlationId = correlationId
        )
    }

    private data class ConnectorStats(val lastRunAt: String? = null, val lastError: String? = null, val runs: Long = 0, val candidates: Long = 0)

    init {
        require(connectorsById.size == connectors.size) { "Connector IDs must be unique" }
        require(maxRequestsPerRun in 1..10_000) { "maxRequestsPerRun must be in 1..10000" }
        require(maxBytesPerRun >= 1_024L) { "maxBytesPerRun must be at least 1 KiB" }
        require(maxCandidatesPerRun in 1..100_000) { "maxCandidatesPerRun must be in 1..100000" }
        require(maxConcurrentRuns == 1) { "Only one source run at a time is currently supported" }
    }

    fun snapshot(): SourceConnectorSnapshot {
        val storedRuns = store.recentRuns(1_000)
        val connectorHealth = connectorsById.values.sortedBy { it.id }.map { connector ->
            val liveStats = connectorStats[connector.id]
            val history = storedRuns.filter { connector.id in it.connectorIds }
            val latest = history.firstOrNull()
            val storedError = latest?.errors?.firstOrNull { it.startsWith("${connector.id}:") }?.substringAfter(": ")
            val lastError = if (liveStats != null) liveStats.lastError else storedError
            val lastRunAt = liveStats?.lastRunAt ?: latest?.finishedAt
            val isEnabled = enabled && connector.configured
            SourceConnectorHealth(
                id = connector.id,
                name = connector.name,
                configured = connector.configured,
                enabled = isEnabled,
                status = when { !enabled -> "disabled_by_operator"; !connector.configured -> "not_configured"; lastError != null -> "degraded"; lastRunAt != null -> "ready"; else -> "configured_not_yet_checked" },
                lastRunAt = lastRunAt,
                lastError = lastError,
                runs = liveStats?.runs ?: history.size.toLong(),
                candidates = liveStats?.candidates ?: store.candidateCountForConnector(connector.id).toLong(),
                note = connector.description
            )
        }
        return SourceConnectorSnapshot(
            enabled = enabled,
            connectors = connectorHealth,
            pendingCandidates = store.pendingCount(),
            recentRuns = store.recentRuns(10),
            budgets = SourceBudgetInfo(maxRequestsPerRun, maxBytesPerRun, maxCandidatesPerRun, maxConcurrentRuns),
            liveSession = liveSession
        )
    }

    fun pendingCandidates(limit: Int, offset: Int): SourceCandidatePage = store.candidates(limit, offset)

    fun run(rawRequest: SourceRunRequest, correlationId: String? = null): SourceRunResponse {
        if (!enabled) throw SourceServiceException(503, "Source learning is disabled", "Set VIBES_SOURCE_CONNECTORS_ENABLED=true to opt in, then restart the server.")
        if (!busy.compareAndSet(false, true)) throw SourceServiceException(409, "A source-learning run is already active", "Wait for the current manually requested run to finish.")
        updateLiveSession(status = "RUNNING", phase = "validating", runId = null, startedAt = clock().toString(), currentConnectorId = null, requests = 0, downloadedBytes = 0, candidatesCreated = 0, checkpointStatus = "not_configured", correlationId = correlationId?.take(128))
        try {
            val query = validateQuery(rawRequest.query)
            val reason = rawRequest.reasonForResearch.trim().uppercase()
            if (reason !in RESEARCH_REASONS) throw SourceServiceException(400, "A valid evidence-gap reason is required", "Use MISSING_EVIDENCE, LOW_CONFIDENCE, or CONTRADICTION.")
            val requested = rawRequest.connectorIds.map { it.trim().lowercase() }.distinct()
            if (requested.isEmpty() || requested.size > 4) throw SourceServiceException(400, "Select between one and four connectors", null)
            val unknown = requested.filterNot(connectorsById::containsKey)
            if (unknown.isNotEmpty()) throw SourceServiceException(400, "Unknown connector id", unknown.joinToString(", "))
            val requestedConnectors = requested.map { connectorsById.getValue(it) }
            val unconfigured = requestedConnectors.filterNot { it.configured }
            if (unconfigured.isNotEmpty()) {
                throw SourceServiceException(
                    503,
                    "One or more selected connectors are not configured",
                    "Configure server-side credentials for: ${unconfigured.joinToString { it.id }}; no research request was sent."
                )
            }
            val selected = requestedConnectors

            val runId = UUID.randomUUID().toString()
            val startedAt = clock().toString()
            val budget = SourceRequestBudget(maxRequestsPerRun, maxBytesPerRun)
            updateLiveSession(runId = runId, phase = "preparing", startedAt = startedAt, checkpointStatus = "run_record_persisted_at_completion")
            val proposed = mutableListOf<SourceCandidate>()
            val errors = mutableListOf<String>()
            val connectorOutcomes = linkedMapOf<String, String?>()
            var pausedForBudget = false
            var attempted = 0
            for (connector in selected) {
                updateLiveSession(phase = "budget_check", currentConnectorId = connector.id, requests = budget.requestCount(), downloadedBytes = budget.bytesRead(), candidatesCreated = proposed.size)
                if (attempted >= maxRequestsPerRun || budget.remainingBytes() <= 0L || proposed.size >= maxCandidatesPerRun) {
                    pausedForBudget = true
                    break
                }
                attempted++
                updateLiveSession(phase = "fetching_metadata", currentConnectorId = connector.id, requests = budget.requestCount(), downloadedBytes = budget.bytesRead(), candidatesCreated = proposed.size)
                try {
                    val results = connector.search(SourceQuery(query, reason), budget)
                    val remaining = maxCandidatesPerRun - proposed.size
                    for (source in results.take(remaining)) {
                        val title = normalize(source.title, 180)
                        val sourceUrl = source.sourceUrl.trim()
                        val excerpt = normalize(source.excerpt, 480)
                        if (title.isBlank() || excerpt.isBlank() || !isSafePublicHttpsUrl(sourceUrl)) continue
                        val contentHash = sha256((title.lowercase() + "\n" + excerpt.lowercase()).trim())
                        proposed += SourceCandidate(
                            id = UUID.randomUUID().toString(), runId = runId, connectorId = connector.id,
                            sourceType = normalize(source.sourceType, 48), title = title, sourceUrl = sourceUrl,
                            excerpt = excerpt, publisher = normalize(source.publisher, 140),
                            licenseNotes = normalize(source.licenseNotes, 500), retrievedAt = clock().toString(),
                            contentSha256 = contentHash, status = "PENDING_REVIEW", reasonForResearch = reason, query = query
                        )
                    }
                    connectorOutcomes[connector.id] = null
                    updateLiveSession(phase = "candidate_validation", currentConnectorId = connector.id, requests = budget.requestCount(), downloadedBytes = budget.bytesRead(), candidatesCreated = proposed.size)
                    if (results.size > remaining) {
                        pausedForBudget = true
                        break
                    }
                } catch (failure: SourceBudgetExceeded) {
                    errors += "${connector.id}: per-run budget reached"
                    connectorOutcomes[connector.id] = "per-run budget reached"
                    pausedForBudget = true
                    break
                } catch (failure: Exception) {
                    val message = safeError(failure)
                    errors += "${connector.id}: $message"
                    connectorOutcomes[connector.id] = message
                }
            }
            if (attempted < selected.size && !pausedForBudget && (budget.requestCount() >= maxRequestsPerRun || proposed.size >= maxCandidatesPerRun || budget.remainingBytes() <= 0L)) pausedForBudget = true
            val finishedAt = clock().toString()
            val status = when {
                pausedForBudget -> "PAUSED_BUDGET"
                proposed.isEmpty() && errors.isNotEmpty() -> "FAILED"
                errors.isNotEmpty() -> "PARTIAL"
                else -> "COMPLETED"
            }
            updateLiveSession(phase = "persisting_candidates", currentConnectorId = null, requests = budget.requestCount(), downloadedBytes = budget.bytesRead(), candidatesCreated = proposed.size,
                checkpointStatus = if (pausedForBudget) "paused_run_record_saved" else "complete_run_record_saving")
            val draftRun = SourceRun(
                id = runId, query = query, reasonForResearch = reason, connectorIds = selected.map { it.id },
                startedAt = startedAt, finishedAt = finishedAt, status = status,
                candidateCount = 0, duplicateCount = 0, requestCount = budget.requestCount(), bytesRead = budget.bytesRead(), errors = errors, correlationId = correlationId?.take(128)
            )
            val (committedRun, inserted) = try { store.commit(draftRun.copy(candidateCount = proposed.size), proposed) }
            catch (failure: Exception) { throw SourceServiceException(500, "Research result could not be saved", "No candidate was promoted. Check source-store permissions, free space and whether the configured source-store candidate capacity has been reached.") }
            connectorOutcomes.forEach { (connectorId, error) ->
                updateStats(connectorId, error, inserted.count { it.connectorId == connectorId }.toLong())
            }
            updateLiveSession(status = committedRun.status, phase = committedRun.status.lowercase(), currentConnectorId = null,
                requests = committedRun.requestCount, downloadedBytes = committedRun.bytesRead, candidatesCreated = inserted.size,
                checkpointStatus = if (committedRun.status == "PAUSED_BUDGET") "paused_run_record_saved_not_resumable" else "completed")
            return SourceRunResponse(run = committedRun, candidates = inserted)
        } finally {
            if (liveSession.status == "RUNNING") updateLiveSession(status = "FAILED", phase = "failed", checkpointStatus = "failed_before_commit")
            busy.set(false)
        }
    }

    private fun updateStats(id: String, error: String?, candidatesAdded: Long) {
        connectorStats.compute(id) { _, old ->
            val state = old ?: ConnectorStats()
            state.copy(
                lastRunAt = clock().toString(),
                lastError = error,
                runs = state.runs + 1,
                candidates = state.candidates + candidatesAdded
            )
        }
    }

    private fun validateQuery(raw: String): String {
        val value = raw.trim()
        if (value.length !in 3..300 || value.any { it.isISOControl() }) {
            throw SourceServiceException(400, "Invalid research query", "Provide a targeted question or weak-claim topic of 3–300 characters.")
        }
        return value
    }

    private fun normalize(raw: String, max: Int): String = raw
        .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim().take(max)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun isSafePublicHttpsUrl(value: String): Boolean {
        if (value.length !in 1..2_048) return false
        return runCatching {
            val uri = java.net.URI(value)
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)
        }.getOrDefault(false)
    }

    private fun safeError(failure: Exception): String = when (failure) {
        is SourceHttpException -> when {
            failure.status == 0 -> "network failure after bounded retries"
            failure.status == 401 || failure.status == 403 -> "provider rejected credentials or access is not permitted (HTTP ${failure.status})"
            else -> "provider request failed (HTTP ${failure.status})"
        }
        is SourceBudgetExceeded -> "per-run request/byte budget reached"
        else -> "${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(140).replace(Regex("(?i)(token|key|secret)=\\S+"), "\$1=[redacted]")}"
    }

    companion object { private val RESEARCH_REASONS = setOf("MISSING_EVIDENCE", "LOW_CONFIDENCE", "CONTRADICTION") }
}

class SourceServiceException(val status: Int, override val message: String, val hint: String?) : RuntimeException(message)
