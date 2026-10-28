package com.vibes.quantum.server.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.time.Instant
import java.util.Locale
import java.util.UUID

@Serializable
private data class EncryptedUnifiedMemoryIndex(val version: Int = 1, val iv: String, val ciphertext: String)

@Serializable
data class UnifiedMemoryRecord(
    val id: String,
    val scopeId: String,
    val kind: String,
    val content: String,
    val contentHash: String,
    val tags: List<String>,
    val sourceType: String,
    val sourceReference: String? = null,
    val confidence: Double = 0.5,
    /** USER_REVIEWED means the user reviewed the memory, not that the statement was fact-checked. */
    val reviewStatus: String = "UNREVIEWED",
    val createdAt: String,
    val updatedAt: String,
    val lastAccessedAt: String? = null,
    val accessCount: Int = 0,
    val expiresAt: String? = null,
    val status: String = "ACTIVE",
    val version: Int = 1
)

@Serializable
data class UnifiedMemoryUpsertRequest(
    val scopeId: String,
    val content: String,
    val kind: String = "NOTE",
    val tags: List<String> = emptyList(),
    val sourceType: String = "USER_PROVIDED",
    val sourceReference: String? = null,
    val confidence: Double = 0.5,
    val userReviewed: Boolean = false,
    val expiresAt: String? = null
)

@Serializable
data class UnifiedMemorySearchHit(val memory: UnifiedMemoryRecord, val score: Double)

@Serializable
data class UnifiedMemoryUserUpsertRequest(
    val content: String,
    val kind: String = "NOTE",
    val tags: List<String> = emptyList(),
    val sourceReference: String? = null,
    val confidence: Double = 0.5,
    val userReviewed: Boolean = false,
    val expiresAt: String? = null,
    val consent: Boolean = false
)

@Serializable
data class UnifiedMemoryUserDeleteResult(val deleted: Int, val recoverable: Boolean = false)

@Serializable
data class UnifiedMemoryForgetRequest(val scopeId: String, val memoryId: String)

@Serializable
data class UnifiedMemoryForgetScopeRequest(val scopeId: String)

@Serializable
data class UnifiedMemoryDeleteResult(val deleted: Int, val scopeId: String)

/**
 * Durable, scope-isolated memory index for preferences, decisions, facts, lessons and episodic notes.
 *
 * This index is deliberately lexical-first and deterministic: it can be replaced by a vector search
 * adapter without changing record/provenance contracts. A user-reviewed record is not automatically
 * treated as a verified fact. Invalid/corrupt persistence fails closed rather than silently starting
 * from an empty index and overwriting recoverable data.
 */
class UnifiedMemoryIndex(
    private val file: Path,
    private val keyHex: String? = System.getenv("VIBES_UNIFIED_MEMORY_KEY_HEX") ?: System.getenv("VIBES_PERSONAL_MEMORY_KEY_HEX")
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false; encodeDefaults = true }
    private val aad = "vibes-unified-memory-index-v1".toByteArray(StandardCharsets.UTF_8)
    private val records = linkedMapOf<String, UnifiedMemoryRecord>()
    private val maxRecords = 50_000
    private val maxRecordsPerScope = 2_000

    init {
        file.parent?.let { Files.createDirectories(it) }
        if (Files.exists(file)) {
            val loaded = try {
                val key = encryptionKey() ?: throw IllegalStateException("Memory encryption key is not configured")
                val envelope = json.decodeFromString<EncryptedUnifiedMemoryIndex>(Files.readString(file, StandardCharsets.UTF_8))
                require(envelope.version == 1) { "Unsupported memory index envelope version" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, Base64.getDecoder().decode(envelope.iv)))
                cipher.updateAAD(aad)
                val plain = cipher.doFinal(Base64.getDecoder().decode(envelope.ciphertext)).toString(StandardCharsets.UTF_8)
                json.decodeFromString<List<UnifiedMemoryRecord>>(plain)
            } catch (failure: Exception) {
                throw IllegalStateException("Unified memory index could not be decrypted/read; original file was preserved", failure)
            }
            require(loaded.size <= maxRecords) { "Unified memory index exceeds the configured record limit" }
            loaded.forEach { record ->
                validateRecord(record)
                require(records.put(record.id, record) == null) { "Unified memory index contains duplicate IDs" }
            }
        }
    }

    fun configured(): Boolean = encryptionKey() != null

    @Synchronized
    fun upsert(request: UnifiedMemoryUpsertRequest): UnifiedMemoryRecord {
        requireConfigured()
        validateScope(request.scopeId)
        val cleanContent = normalizeWhitespace(request.content).take(MAX_CONTENT_CHARS)
        require(cleanContent.isNotBlank()) { "Memory content cannot be blank" }
        require(request.content.length <= MAX_CONTENT_CHARS * 2) { "Memory content exceeds the input size limit" }
        require(!looksLikeCredential(cleanContent)) { "Credential-like content cannot be saved to memory" }
        val kind = safeLabel(request.kind, "kind")
        val sourceType = safeLabel(request.sourceType, "sourceType")
        require(request.confidence.isFinite() && request.confidence in 0.0..1.0) { "confidence must be between 0 and 1" }
        require(request.tags.size <= 40) { "At most 40 tags may be supplied" }
        val tags = request.tags.map { normalizeWhitespace(it).take(64) }
            .filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }.take(20)
        val sourceReference = request.sourceReference?.trim()?.takeIf { it.isNotBlank() }?.also {
            require(it.length <= 500 && it.none(Char::isISOControl)) { "Invalid sourceReference" }
        }
        val expiresAt = request.expiresAt?.also { value ->
            require(value.length <= 80 && runCatching { Instant.parse(value) }.isSuccess) { "expiresAt must be an ISO-8601 instant" }
        }
        val hash = sha256(normalizeForHash(cleanContent))
        val duplicate = records.values.firstOrNull {
            it.scopeId == request.scopeId && it.kind == kind && it.contentHash == hash && it.status == "ACTIVE"
        }
        val now = Instant.now().toString()
        val record = if (duplicate != null) {
            duplicate.copy(
                content = cleanContent,
                tags = (duplicate.tags + tags).distinctBy { it.lowercase(Locale.ROOT) }.take(20),
                sourceType = sourceType,
                sourceReference = sourceReference ?: duplicate.sourceReference,
                confidence = maxOf(duplicate.confidence, request.confidence),
                reviewStatus = if (request.userReviewed) "USER_REVIEWED" else duplicate.reviewStatus,
                updatedAt = now,
                expiresAt = expiresAt ?: duplicate.expiresAt,
                version = duplicate.version + 1
            )
        } else {
            require(records.size < maxRecords) { "Unified memory index capacity reached" }
            require(records.values.count { it.scopeId == request.scopeId && it.status == "ACTIVE" } < maxRecordsPerScope) {
                "Memory capacity reached for this scope"
            }
            UnifiedMemoryRecord(
                id = "mem_" + UUID.randomUUID().toString().replace("-", "").take(24),
                scopeId = request.scopeId,
                kind = kind,
                content = cleanContent,
                contentHash = hash,
                tags = tags,
                sourceType = sourceType,
                sourceReference = sourceReference,
                confidence = request.confidence,
                reviewStatus = if (request.userReviewed) "USER_REVIEWED" else "UNREVIEWED",
                createdAt = now,
                updatedAt = now,
                expiresAt = expiresAt
            )
        }
        val previous = records[record.id]
        records[record.id] = record
        try {
            persist()
        } catch (failure: Exception) {
            if (previous == null) records.remove(record.id) else records[record.id] = previous
            throw IllegalStateException("Memory write failed; the previous index was restored", failure)
        }
        return record
    }

    @Synchronized
    fun search(scopeId: String, query: String, limit: Int = 8): List<UnifiedMemorySearchHit> {
        requireConfigured()
        validateScope(scopeId)
        require(limit in 1..50) { "limit must be between 1 and 50" }
        val queryText = normalizeWhitespace(query).take(2_000)
        if (queryText.isBlank()) return emptyList()
        val queryTerms = terms(queryText)
        if (queryTerms.isEmpty()) return emptyList()
        val now = Instant.now()
        val hits = records.values.asSequence()
            .filter { it.scopeId == scopeId && it.status == "ACTIVE" && !isExpired(it, now) }
            .mapNotNull { record ->
                val contentTerms = terms(record.content)
                val tagTerms = record.tags.flatMap(::terms).toSet()
                val overlap = queryTerms.intersect(contentTerms)
                val tagOverlap = queryTerms.intersect(tagTerms)
                val phraseBoost = if (record.content.lowercase(Locale.ROOT).contains(queryText.lowercase(Locale.ROOT))) 0.25 else 0.0
                if (overlap.isEmpty() && tagOverlap.isEmpty() && phraseBoost == 0.0) null
                else {
                    val recall = if (queryTerms.isEmpty()) 0.0 else overlap.size.toDouble() / queryTerms.size
                    val precision = if (contentTerms.isEmpty()) 0.0 else overlap.size.toDouble() / contentTerms.size
                    val tagScore = if (queryTerms.isEmpty()) 0.0 else tagOverlap.size.toDouble() / queryTerms.size
                    val score = (0.58 * recall + 0.17 * precision + 0.15 * tagScore + 0.10 * record.confidence + phraseBoost)
                        .coerceIn(0.0, 1.0)
                    UnifiedMemorySearchHit(record, score)
                }
            }
            .sortedWith(compareByDescending<UnifiedMemorySearchHit> { it.score }
                .thenByDescending { it.memory.updatedAt }
                .thenBy { it.memory.id })
            .take(limit)
            .toList()
        if (hits.isNotEmpty()) {
            val nowText = now.toString()
            hits.forEach { hit ->
                records[hit.memory.id] = hit.memory.copy(
                    lastAccessedAt = nowText,
                    accessCount = (hit.memory.accessCount + 1).coerceAtMost(1_000_000)
                )
            }
            persist()
        }
        return hits.map { it.copy(memory = records.getValue(it.memory.id)) }
    }

    @Synchronized
    fun list(scopeId: String, includeExpired: Boolean = false): List<UnifiedMemoryRecord> {
        requireConfigured()
        validateScope(scopeId)
        val now = Instant.now()
        return records.values.filter {
            it.scopeId == scopeId && it.status == "ACTIVE" && (includeExpired || !isExpired(it, now))
        }.sortedByDescending { it.updatedAt }
    }

    /** Hard-deletes both the memory record and its index data for the specified scope. */
    @Synchronized
    fun forget(scopeId: String, memoryId: String): Boolean {
        requireConfigured()
        validateScope(scopeId)
        val existing = records[memoryId] ?: return false
        if (existing.scopeId != scopeId) return false
        records.remove(memoryId)
        try {
            persist()
        } catch (failure: Exception) {
            records[memoryId] = existing
            throw IllegalStateException("Memory deletion failed; the previous index was restored", failure)
        }
        return true
    }

    /** Deletes all records for a scope; no raw scope identifier is stored inside the record content. */
    @Synchronized
    fun forgetScope(scopeId: String): Int {
        requireConfigured()
        validateScope(scopeId)
        val removed = records.values.filter { it.scopeId == scopeId }
        if (removed.isEmpty()) return 0
        removed.forEach { records.remove(it.id) }
        try {
            persist()
        } catch (failure: Exception) {
            removed.forEach { records[it.id] = it }
            throw IllegalStateException("Scope deletion failed; the previous index was restored", failure)
        }
        return removed.size
    }

    @Synchronized
    fun stats(): Map<String, Int> = mapOf("configured" to if (configured()) 1 else 0, "records" to records.size, "scopes" to records.values.map { it.scopeId }.distinct().size)

    private fun validateRecord(record: UnifiedMemoryRecord) {
        validateScope(record.scopeId)
        require(record.id.matches(Regex("mem_[A-Za-z0-9]{8,32}"))) { "Invalid memory record ID" }
        require(record.kind.matches(LABEL_PATTERN) && record.sourceType.matches(LABEL_PATTERN)) { "Invalid memory labels" }
        require(record.content.isNotBlank() && record.content.length <= MAX_CONTENT_CHARS) { "Invalid memory content" }
        require(record.contentHash == sha256(normalizeForHash(record.content))) { "Stored memory content hash mismatch" }
        require(record.confidence.isFinite() && record.confidence in 0.0..1.0) { "Invalid memory confidence" }
        require(record.reviewStatus in setOf("UNREVIEWED", "USER_REVIEWED")) { "Invalid memory review status" }
        require(record.status == "ACTIVE") { "Unexpected memory lifecycle state" }
        require(record.version >= 1 && record.accessCount >= 0) { "Invalid memory version or access count" }
        require(runCatching { Instant.parse(record.createdAt) }.isSuccess && runCatching { Instant.parse(record.updatedAt) }.isSuccess) { "Invalid timestamps" }
        record.expiresAt?.let { require(runCatching { Instant.parse(it) }.isSuccess) { "Invalid expiration timestamp" } }
        record.lastAccessedAt?.let { require(runCatching { Instant.parse(it) }.isSuccess) { "Invalid lastAccessedAt" } }
        require(record.tags.size <= 20 && record.tags.all { it.length <= 64 && it.none(Char::isISOControl) }) { "Invalid memory tags" }
    }

    private fun persist() {
        val key = encryptionKey() ?: throw IllegalStateException("Memory encryption key is not configured")
        file.parent?.let { Files.createDirectories(it) }
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(json.encodeToString(records.values.toList()).toByteArray(StandardCharsets.UTF_8))
        val payload = json.encodeToString(EncryptedUnifiedMemoryIndex(
            iv = Base64.getEncoder().encodeToString(iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext)
        ))
        Files.writeString(temp, payload, StandardCharsets.UTF_8)
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun requireConfigured() {
        check(configured()) { "Memory encryption is not configured; set VIBES_UNIFIED_MEMORY_KEY_HEX" }
    }

    private fun encryptionKey(): ByteArray? {
        val raw = keyHex?.trim() ?: return null
        if (!raw.matches(Regex("[0-9a-fA-F]{64}"))) return null
        val master = raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // Domain-separate this key from the personal-memory vault even if the same root secret is used.
        return MessageDigest.getInstance("SHA-256").digest(master + "|vibes-unified-memory-index-v1".toByteArray(StandardCharsets.UTF_8))
    }

    private fun validateScope(scope: String) {
        require(scope.matches(Regex("[A-Za-z0-9._:-]{8,128}"))) {
            "scopeId must be an app-scoped pseudonymous identifier of 8–128 safe characters"
        }
    }

    private fun safeLabel(value: String, field: String): String {
        val clean = value.trim().uppercase(Locale.ROOT)
        require(clean.matches(LABEL_PATTERN) && clean.length <= 40) { "Invalid $field label" }
        return clean
    }

    private fun normalizeWhitespace(value: String): String = value.trim().replace(Regex("\\s+"), " ")
    private fun normalizeForHash(value: String): String = normalizeWhitespace(value).lowercase(Locale.ROOT)
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun isExpired(record: UnifiedMemoryRecord, now: Instant): Boolean =
        record.expiresAt?.let { runCatching { Instant.parse(it).isBefore(now) }.getOrDefault(true) } ?: false

    private fun terms(value: String): Set<String> = Regex("[\\p{L}\\p{N}]{2,}").findAll(value.lowercase(Locale.ROOT))
        .map { it.value }.filter { it !in STOP_WORDS }.toSet()

    private fun looksLikeCredential(value: String): Boolean =
        Regex("-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----").containsMatchIn(value) ||
            Regex("\\bAKIA[0-9A-Z]{16}\\b").containsMatchIn(value) ||
            Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{24,}={0,2}").containsMatchIn(value) ||
            Regex("(?i)\\b(?:api[_-]?key|access[_-]?token|client[_-]?secret|password)\\s*[:=]\\s*[\"'][^\"'\\s]{20,}[\"']").containsMatchIn(value)

    companion object {
        private const val MAX_CONTENT_CHARS = 6_000
        private val LABEL_PATTERN = Regex("[A-Z0-9][A-Z0-9_.:-]{0,39}")
        private val STOP_WORDS = setOf("the", "and", "for", "with", "from", "that", "this", "you", "your", "are", "was", "were", "what", "when", "where", "how", "why", "does", "can", "will", "have", "has", "had")
    }
}
