package com.vibes.quantum.server.sources

import kotlinx.serialization.Serializable

/** Only minimal metadata and short excerpts are retained; source text is always untrusted. */
@Serializable
data class SourceCandidate(
    val id: String,
    val runId: String,
    val connectorId: String,
    val sourceType: String,
    val title: String,
    val sourceUrl: String,
    val excerpt: String,
    val publisher: String,
    val licenseNotes: String,
    val retrievedAt: String,
    val contentSha256: String,
    val status: String = "PENDING_REVIEW",
    val reasonForResearch: String,
    val query: String
)

@Serializable
data class SourceRun(
    val id: String,
    val query: String,
    val reasonForResearch: String,
    val connectorIds: List<String>,
    val startedAt: String,
    val finishedAt: String,
    val status: String,
    val candidateCount: Int,
    val duplicateCount: Int,
    val requestCount: Int,
    val bytesRead: Long,
    val errors: List<String>,
    /** Non-secret request/job trace identifier; never a prompt or user-content label. */
    val correlationId: String? = null
)

@Serializable
data class SourceConnectorHealth(
    val id: String,
    val name: String,
    val configured: Boolean,
    val enabled: Boolean,
    val status: String,
    val lastRunAt: String? = null,
    val lastError: String? = null,
    val runs: Long = 0,
    val candidates: Long = 0,
    val note: String = ""
)

@Serializable
data class SourceConnectorSnapshot(
    val enabled: Boolean,
    val connectors: List<SourceConnectorHealth>,
    val pendingCandidates: Int,
    val recentRuns: List<SourceRun>,
    val budgets: SourceBudgetInfo,
    val liveSession: LiveSourceLearningSession? = null
)

@Serializable
data class LiveSourceLearningSession(
    val runId: String? = null,
    val status: String = "IDLE",
    val phase: String = "idle",
    val currentConnectorId: String? = null,
    val startedAt: String? = null,
    val updatedAt: String,
    val requests: Int = 0,
    val downloadedBytes: Long = 0,
    val candidatesCreated: Int = 0,
    val checkpointStatus: String = "not_configured",
    val note: String = "Live counters describe explicit source-learning runs only; they do not include user chat content.",
    val correlationId: String? = null
)

@Serializable
data class SourceBudgetInfo(
    val maxRequestsPerRun: Int,
    val maxBytesPerRun: Long,
    val maxCandidatesPerRun: Int,
    val maxConcurrentRuns: Int
)

@Serializable
data class SourceRunRequest(
    val query: String,
    /** Must explain a real evidence gap. Supported values: MISSING_EVIDENCE, LOW_CONFIDENCE, CONTRADICTION. */
    val reasonForResearch: String,
    val connectorIds: List<String> = listOf("wikipedia")
)

@Serializable
data class SourceRunResponse(
    val run: SourceRun,
    val candidates: List<SourceCandidate>,
    val note: String = "Candidates are untrusted evidence and require human review; none were promoted to approved knowledge."
)

@Serializable
data class SourceCandidatePage(
    val count: Int,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val candidates: List<SourceCandidate>
)

/** Data supplied by a connector before source fields are normalized and stored. */
data class ConnectorSource(
    val sourceType: String,
    val title: String,
    val sourceUrl: String,
    val excerpt: String,
    val publisher: String,
    val licenseNotes: String,
    val externalId: String = ""
)

data class SourceQuery(val query: String, val reasonForResearch: String)
