package com.vibes.quantum.server.sources

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** Bounded JSON store with atomic replacement. Candidate status is always pending review on ingestion. */
class SourceCandidateStore(
    fileName: String? = System.getenv("VIBES_SOURCE_STORE_FILE") ?: "data/vibes-source-learning.json",
    private val maxCandidates: Int = System.getenv("VIBES_SOURCE_STORE_MAX_CANDIDATES")?.toIntOrNull() ?: 10_000,
    private val maxRuns: Int = System.getenv("VIBES_SOURCE_STORE_MAX_RUNS")?.toIntOrNull() ?: 1_000,
    private val maxStoreBytes: Long = System.getenv("VIBES_SOURCE_STORE_MAX_BYTES")?.toLongOrNull() ?: 64L * 1024L * 1024L
) {
    private val path: Path? = fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }
    private var candidates: List<SourceCandidate> = emptyList()
    private var runs: List<SourceRun> = emptyList()

    init {
        require(maxCandidates in 1..1_000_000) { "maxCandidates must be in 1..1000000" }
        require(maxRuns in 1..100_000) { "maxRuns must be in 1..100000" }
        require(maxStoreBytes >= 1_024L) { "maxStoreBytes must be at least 1 KiB" }
        load()
    }

    @Synchronized fun pendingCount(): Int = candidates.count { it.status == "PENDING_REVIEW" }
    @Synchronized fun recentRuns(limit: Int = 25): List<SourceRun> = runs.takeLast(limit.coerceIn(1, maxRuns)).reversed()
    @Synchronized fun candidateCountForConnector(connectorId: String): Int = candidates.count { it.connectorId == connectorId }
    @Synchronized fun candidates(limit: Int = 50, offset: Int = 0): SourceCandidatePage {
        val safeLimit = limit.coerceIn(1, 100)
        val safeOffset = offset.coerceAtLeast(0)
        val pending = candidates.filter { it.status == "PENDING_REVIEW" }.asReversed()
        return SourceCandidatePage(
            count = pending.drop(safeOffset).take(safeLimit).size,
            total = pending.size,
            limit = safeLimit,
            offset = safeOffset,
            candidates = pending.drop(safeOffset).take(safeLimit)
        )
    }

    @Synchronized fun commit(run: SourceRun, proposed: List<SourceCandidate>): Pair<SourceRun, List<SourceCandidate>> {
        val priorCandidates = candidates
        val priorRuns = runs
        val knownUrls = candidates.map { normalizeUrl(it.sourceUrl) }.toMutableSet()
        val knownHashes = candidates.map { it.contentSha256 }.toMutableSet()
        var duplicates = 0
        val inserted = mutableListOf<SourceCandidate>()
        for (item in proposed) {
            val urlKey = normalizeUrl(item.sourceUrl)
            val duplicate = urlKey in knownUrls || item.contentSha256 in knownHashes ||
                (candidates + inserted).any { existing -> isNearDuplicate(existing, item) }
            if (duplicate) {
                duplicates += 1
            } else {
                knownUrls += urlKey
                knownHashes += item.contentSha256
                inserted += item
            }
        }
        if (candidates.size + inserted.size > maxCandidates) {
            throw IllegalStateException("Source candidate store is full. Review or archive pending items before running more research.")
        }
        val committedRun = run.copy(candidateCount = inserted.size, duplicateCount = run.duplicateCount + duplicates)
        // Never evict pending evidence silently: quota exhaustion must be visible to the operator.
        candidates = candidates + inserted
        runs = (runs + committedRun).takeLast(maxRuns)
        try {
            persist()
        } catch (failure: Exception) {
            candidates = priorCandidates
            runs = priorRuns
            throw IllegalStateException("Could not persist source research results; no candidates were committed", failure)
        }
        return committedRun to inserted
    }

    private fun load() {
        val target = path ?: return
        if (!Files.exists(target)) return
        try {
            val fileSize = Files.size(target)
            require(fileSize <= maxStoreBytes) { "Saved source-learning store exceeds the $maxStoreBytes-byte safety bound" }
            val root = json.parseToJsonElement(Files.readString(target, StandardCharsets.UTF_8)).jsonObject
            val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
            require(schemaVersion == STORE_SCHEMA_VERSION) { "Unsupported source-learning store schema version $schemaVersion" }
            val parsedCandidates = json.decodeFromJsonElement(ListSerializer(SourceCandidate.serializer()), root.getValue("candidates"))
            val parsedRuns = json.decodeFromJsonElement(ListSerializer(SourceRun.serializer()), root.getValue("runs"))
            require(parsedCandidates.size <= maxCandidates && parsedRuns.size <= maxRuns) { "Saved source-learning store exceeds configured bounds" }
            require(parsedCandidates.all { it.status == "PENDING_REVIEW" || it.status == "REVIEWED" }) { "Saved candidate has an unsupported status" }
            candidates = parsedCandidates
            runs = parsedRuns
        } catch (failure: Exception) {
            throw IllegalStateException("Could not load source-learning store. Back up and repair it before restarting.", failure)
        }
    }

    private fun persist() {
        val target = path ?: return
        val parent = target.toAbsolutePath().parent
        Files.createDirectories(parent)
        val content = """{"schemaVersion":$STORE_SCHEMA_VERSION,"candidates":${json.encodeToString(ListSerializer(SourceCandidate.serializer()), candidates)},"runs":${json.encodeToString(ListSerializer(SourceRun.serializer()), runs)}}"""
        val temporary = Files.createTempFile(parent, target.fileName.toString(), ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
            runCatching { Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun isNearDuplicate(first: SourceCandidate, second: SourceCandidate): Boolean {
        val a = tokens("${first.title} ${first.excerpt}")
        val b = tokens("${second.title} ${second.excerpt}")
        if (a.size < 6 || b.size < 6) return false
        val smaller = minOf(a.size, b.size).toDouble()
        val shared = a.intersect(b).size.toDouble()
        // High-overlap text is a duplicate candidate, not an automatic truth/confidence signal.
        return shared / maxOf(a.size, b.size).toDouble() >= 0.88 && shared >= smaller * 0.88
    }

    private fun tokens(value: String): Set<String> = Regex("[\\p{L}\\p{N}]{3,}")
        .findAll(value.lowercase())
        .map { it.value }
        .toSet()

    private fun normalizeUrl(value: String): String = value.trim().trimEnd('/').lowercase()

    companion object {
        private const val STORE_SCHEMA_VERSION = 1
    }
}
