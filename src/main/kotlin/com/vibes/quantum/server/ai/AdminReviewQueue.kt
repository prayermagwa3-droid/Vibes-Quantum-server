package com.vibes.quantum.server.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Serializable
data class AdminReviewItem(
    val id: String,
    val kind: String,
    val title: String,
    val summary: String,
    val createdAt: String,
    val status: String = "pending",
    val resolution: String? = null,
    val reviewedAt: String? = null
)

@Serializable
data class AdminReviewQueueResponse(val items: List<AdminReviewItem>, val pending: Int, val total: Int)

@Serializable
data class AdminReviewRequest(val status: String, val resolution: String)

/** Stores minimal metadata only. User prompts, provider messages, credentials and source bodies are never queued. */
class AdminReviewQueue(fileName: String? = System.getenv("VIBES_COUNCIL_REVIEW_FILE") ?: "data/vibes-council-review.json", private val maxEntries: Int = 1_000) {
    private val lock = Any()
    private val path: Path? = fileName?.takeIf { it.isNotBlank() }?.let(Paths::get)
    private val items = mutableListOf<AdminReviewItem>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }

    init { load() }

    fun enqueue(kind: String, title: String, summary: String): AdminReviewItem = synchronized(lock) {
        require(kind in setOf("claim_review", "conflict", "evaluation_failure"))
        val cleanTitle = sanitize(title).take(180).ifBlank { "Review required" }
        val cleanSummary = sanitize(summary).take(600)
        val digest = digest("$kind|$cleanTitle|$cleanSummary")
        val duplicate = items.firstOrNull { it.status == "pending" && digest("${it.kind}|${it.title}|${it.summary}") == digest }
        if (duplicate != null) return duplicate
        val item = AdminReviewItem("review_" + UUID.randomUUID().toString().replace("-", "").take(20), kind, cleanTitle, cleanSummary, Instant.now().toString())
        items += item
        while (items.size > maxEntries.coerceIn(50, 5_000)) items.removeAt(0)
        persist()
        item
    }

    fun snapshot(limit: Int = 100): AdminReviewQueueResponse = synchronized(lock) {
        val selected = items.sortedByDescending { it.createdAt }.take(limit.coerceIn(1, 200))
        AdminReviewQueueResponse(selected, items.count { it.status == "pending" }, items.size)
    }

    fun review(id: String, request: AdminReviewRequest): AdminReviewItem? = synchronized(lock) {
        require(request.status in setOf("reviewed", "dismissed"))
        require(request.resolution.isNotBlank() && request.resolution.length <= 1_000)
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val saved = items[index].copy(status = request.status, resolution = sanitize(request.resolution), reviewedAt = Instant.now().toString())
        items[index] = saved
        persist()
        saved
    }

    private fun load() {
        val target = path ?: return
        if (!Files.exists(target)) return
        try {
            val saved = json.decodeFromString(ListSerializer(AdminReviewItem.serializer()), Files.readString(target, StandardCharsets.UTF_8))
            require(saved.size <= 5_000)
            saved.forEach { item ->
                require(item.id.matches(Regex("review_[A-Za-z0-9]{10,40}")))
                require(item.kind in setOf("claim_review", "conflict", "evaluation_failure"))
                require(item.status in setOf("pending", "reviewed", "dismissed"))
            }
            items += saved.takeLast(maxEntries.coerceIn(50, 5_000))
        } catch (failure: Exception) {
            throw IllegalStateException("Could not load the council review queue; back up and repair its file before restarting.", failure)
        }
    }

    private fun persist() {
        val target = path ?: return
        val absolute = target.toAbsolutePath().normalize()
        val parent = absolute.parent ?: throw IllegalStateException("Review queue directory is missing")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "vibes-review-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(ListSerializer(AdminReviewItem.serializer()), items), StandardCharsets.UTF_8)
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING) }
            runCatching { Files.setPosixFilePermissions(absolute, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("(?i)(api[_ -]?key|access[_ -]?token|password|secret)\\s*[:=]\\s*[^\\s,;]+"), "credential=[REDACTED]")
        .replace(Regex("\\b(sk-[A-Za-z0-9_-]{12,}|AIza[0-9A-Za-z_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|vq_live_[A-Za-z0-9_-]{20,})\\b"), "[REDACTED_CREDENTIAL]")
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
