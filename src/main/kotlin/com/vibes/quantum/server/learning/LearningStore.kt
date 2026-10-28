package com.vibes.quantum.server.learning

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private val storeJson = Json { prettyPrint = true; ignoreUnknownKeys = false }

@Serializable
data class StoredConsent(
    val appId: String,
    val subjectHash: String,
    val granted: Boolean,
    val categories: List<String>,
    val noticeVersion: String,
    val updatedAt: String
)

@Serializable
internal data class LearningStoreFile(
    val policies: List<LearningPolicy> = emptyList(),
    val consents: List<StoredConsent> = emptyList(),
    val feedback: List<LearningFeedbackRecord> = emptyList()
)

class LearningStore(fileName: String?) {
    private val path: Path? = fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
    private val policies = linkedMapOf<String, LearningPolicy>()
    private val consents = linkedMapOf<String, StoredConsent>()
    private val feedback = linkedMapOf<String, LearningFeedbackRecord>()

    init { load() }

    @Synchronized
    fun policy(appId: String): LearningPolicy = policies[appId] ?: LearningPolicy(
        appId = appId, enabled = false, updatedAt = Instant.EPOCH.toString()
    )

    @Synchronized
    fun updatePolicy(appId: String, update: LearningPolicyUpdate): LearningPolicy {
        require(appId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "Invalid app ID" }
        require(update.retentionDays in 1..365) { "retentionDays must be between 1 and 365" }
        require(update.dailyQuota in 1..100_000) { "dailyQuota must be between 1 and 100000" }
        require(update.policyVersion.matches(Regex("[A-Za-z0-9._-]{1,32}"))) { "Invalid policyVersion" }
        require(update.allowedCategories.isNotEmpty() && update.allowedCategories.distinct().size == update.allowedCategories.size && update.allowedCategories.all { it in ALLOWED_LEARNING_CATEGORIES }) {
            "allowedCategories must be a non-empty unique subset of rating, task_outcome, error_category, teaching_example"
        }
        val previous = policies[appId]
        val normalizedCategories = update.allowedCategories.sorted()
        if (previous != null && update.policyVersion == previous.policyVersion &&
            ((update.enabled && !previous.enabled) || update.allowedCategories.sorted() != previous.allowedCategories || update.retentionDays != previous.retentionDays)) {
            throw IllegalArgumentException("Increment policyVersion when enabling learning or changing allowed categories or retention so subjects must consent again")
        }
        val result = LearningPolicy(appId, update.enabled, normalizedCategories, update.retentionDays, update.dailyQuota, Instant.now().toString(), update.policyVersion)
        policies[appId] = result
        try { persist() } catch (failure: Exception) { if (previous == null) policies.remove(appId) else policies[appId] = previous; throw failure }
        return result
    }

    @Synchronized
    fun setConsent(appId: String, subjectRef: String, granted: Boolean, categories: List<String>, noticeVersion: String): Pair<StoredConsent, Int> {
        require(subjectRef.isNotBlank() && subjectRef.length <= 256 && subjectRef.none { it.isISOControl() }) { "subjectRef must be a stable app-scoped pseudonymous reference of 1–256 characters" }
        require(noticeVersion.matches(Regex("[A-Za-z0-9._-]{1,32}"))) { "Invalid noticeVersion" }
        val policy = policy(appId)
        if (granted) require(policy.enabled) { "Shared learning is disabled for this app by the administrator" }
        val allowed = categories.distinct().sorted()
        if (granted) {
            require(noticeVersion == policy.policyVersion) { "noticeVersion must match the current app policy version; request fresh consent" }
            require(allowed.isNotEmpty() && allowed.size == categories.size && allowed.all { it in policy.allowedCategories }) {
                "Consent categories must be a unique, non-empty subset of the app's enabled policy categories"
            }
        }
        val hash = subjectHash(appId, subjectRef)
        val key = consentKey(appId, hash)
        val previous = consents[key]
        val now = Instant.now().toString()
        val record = StoredConsent(appId, hash, granted, if (granted) allowed else emptyList(), noticeVersion, now)
        consents[key] = record
        val removedRows = if (granted) emptyList() else feedback.values.filter { it.appId == appId && it.subjectHash == hash }
        removedRows.forEach { feedback.remove(it.id) }
        try { persist() } catch (failure: Exception) {
            if (previous == null) consents.remove(key) else consents[key] = previous
            removedRows.forEach { feedback[it.id] = it }
            throw failure
        }
        val deleted = removedRows.size
        return record to deleted
    }

    @Synchronized
    fun consentStatus(appId: String, subjectRef: String): LearningConsentStatus? {
        val hash = subjectHash(appId, subjectRef)
        val record = consents[consentKey(appId, hash)] ?: return null
        return LearningConsentStatus(record.appId, record.granted, record.categories, record.noticeVersion, record.updatedAt)
    }

    @Synchronized
    fun addFeedback(request: LearningFeedbackRequest): LearningFeedbackAccepted {
        purgeExpired()
        val appId = request.appId
        val policy = policy(appId)
        require(policy.enabled) { "Shared learning is disabled for this app" }
        require(request.category in ALLOWED_LEARNING_CATEGORIES && request.category in policy.allowedCategories) { "This signal category is not enabled for this app" }
        require(request.signalType in ALLOWED_SIGNAL_TYPES && request.signalType == request.category) { "signalType must match the selected category" }
        val hash = subjectHash(appId, request.subjectRef)
        val consent = consents[consentKey(appId, hash)]
        if (consent?.granted != true || request.category !in consent.categories || consent.noticeVersion != policy.policyVersion) throw LearningConsentRequired("No active consent for this app, subject and signal category under the current policy version")
        val signal = when (request.signalType) {
            "rating" -> {
                require(request.rating == "up" || request.rating == "down") { "rating must be 'up' or 'down'" }
                require(request.taskOutcome == null && request.errorCategory == null && request.teachingExample == null) { "Only the selected signal field may be supplied" }
                "rating"
            }
            "task_outcome" -> {
                require(request.taskOutcome == "success" || request.taskOutcome == "failure" || request.taskOutcome == "partial") { "taskOutcome must be success, failure or partial" }
                require(request.rating == null && request.errorCategory == null && request.teachingExample == null) { "Only the selected signal field may be supplied" }
                "task_outcome"
            }
            "error_category" -> {
                val error = request.errorCategory?.trim().orEmpty()
                require(error.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "errorCategory must be a short code, not free text" }
                require(request.rating == null && request.taskOutcome == null && request.teachingExample == null) { "Only the selected signal field may be supplied" }
                "error_category"
            }
            else -> {
                val example = request.teachingExample?.trim().orEmpty()
                require(example.length in 8..500 && example.none { it == '\u0000' }) { "teachingExample must be explicitly submitted text of 8–500 characters" }
                require(request.rating == null && request.taskOutcome == null && request.errorCategory == null) { "Only the selected signal field may be supplied" }
                "teaching_example"
            }
        }
        val suppliedKey = request.idempotencyKey?.trim()
        if (suppliedKey != null) require(suppliedKey.length in 8..128 && suppliedKey.matches(Regex("[A-Za-z0-9._:-]+"))) { "idempotencyKey must be 8–128 safe characters" }
        val digest = suppliedKey?.let { sha256("$appId:$hash:$it") }
        if (digest != null) {
            feedback.values.firstOrNull { it.appId == appId && it.idempotencyDigest == digest }?.let { existing ->
                return LearningFeedbackAccepted(true, duplicate = true, id = existing.id, status = existing.status)
            }
        }
        // Idempotency keys are helpful, but must not allow a client to inflate repeated identical ratings
        // by generating a new key for every click.
        val now = Instant.now()
        feedback.values.firstOrNull {
            it.appId == appId && it.subjectHash == hash && it.category == signal &&
                Duration.between(Instant.parse(it.createdAt), now).seconds in 0L..300L &&
                it.rating == request.rating && it.taskOutcome == request.taskOutcome && it.errorCategory == request.errorCategory && it.teachingExample == request.teachingExample?.trim()
        }?.let { existing -> return LearningFeedbackAccepted(true, duplicate = true, id = existing.id, status = existing.status) }
        val today = LocalDate.now(ZoneOffset.UTC)
        val todayCount = feedback.values.count { it.appId == appId && runCatching { LocalDate.parse(it.createdAt.take(10)) == today }.getOrDefault(false) }
        if (todayCount >= policy.dailyQuota) throw LearningQuotaExceeded("Daily learning feedback quota reached for this app")
        val status = if (signal == "teaching_example") "PENDING_REVIEW" else "RECORDED"
        val row = LearningFeedbackRecord(
            id = "lf_" + UUID.randomUUID().toString().replace("-", "").take(20), appId = appId, subjectHash = hash,
            category = signal, signalType = signal, rating = request.rating, taskOutcome = request.taskOutcome,
            errorCategory = request.errorCategory?.trim(), teachingExample = request.teachingExample?.trim(), status = status,
            createdAt = now.toString(), expiresAt = now.plus(Duration.ofDays(policy.retentionDays.toLong())).toString(), idempotencyDigest = digest
        )
        feedback[row.id] = row
        try { persist() } catch (failure: Exception) { feedback.remove(row.id); throw failure }
        return LearningFeedbackAccepted(true, id = row.id, status = row.status)
    }

    @Synchronized
    fun exportSubject(appId: String, subjectRef: String): LearningSubjectExport {
        validateSubject(subjectRef)
        purgeExpired()
        val hash = subjectHash(appId, subjectRef)
        val stored = consents[consentKey(appId, hash)]
        val consent = stored?.let { LearningConsentStatus(it.appId, it.granted, it.categories, it.noticeVersion, it.updatedAt) }
        return LearningSubjectExport(appId, consent, feedback.values.filter { it.appId == appId && it.subjectHash == hash }.sortedBy { it.createdAt }, Instant.now().toString())
    }

    @Synchronized
    fun deleteSubject(appId: String, subjectRef: String): Int {
        validateSubject(subjectRef)
        val hash = subjectHash(appId, subjectRef)
        val previousConsent = consents.remove(consentKey(appId, hash))
        val matches = feedback.values.filter { it.appId == appId && it.subjectHash == hash }
        matches.forEach { feedback.remove(it.id) }
        try { persist() } catch (failure: Exception) {
            if (previousConsent != null) consents[consentKey(appId, hash)] = previousConsent
            matches.forEach { feedback[it.id] = it }
            throw failure
        }
        return matches.size
    }

    @Synchronized
    fun page(appId: String, limit: Int, offset: Int): Pair<Int, List<LearningFeedbackRecord>> {
        purgeExpired()
        val rows = feedback.values.filter { it.appId == appId }.sortedByDescending { it.createdAt }
        return rows.size to rows.drop(offset).take(limit)
    }

    @Synchronized
    fun summary(appId: String): LearningSummary {
        purgeExpired()
        val p = policy(appId)
        val rows = feedback.values.filter { it.appId == appId }
        val today = LocalDate.now(ZoneOffset.UTC)
        val consentsForApp = consents.values.filter { it.appId == appId }
        return LearningSummary(
            appId, p.enabled, p.allowedCategories, rows.size, rows.count { it.category == "teaching_example" && it.status == "PENDING_REVIEW" },
            rows.count { runCatching { LocalDate.parse(it.createdAt.take(10)) == today }.getOrDefault(false) }, p.dailyQuota,
            consentsForApp.count { it.granted }, p.updatedAt
        )
    }

    private fun validateSubject(value: String) { require(value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() }) { "Invalid subjectRef" } }
    private fun consentKey(appId: String, hash: String) = "$appId:$hash"
    private fun subjectHash(appId: String, raw: String): String {
        validateSubject(raw)
        return sha256("vq-learning-subject-v1:$appId:$raw")
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun purgeExpired() {
        val now = Instant.now()
        val expired = feedback.values.filter { runCatching { Instant.parse(it.expiresAt).isBefore(now) }.getOrDefault(true) }
        if (expired.isNotEmpty()) {
            expired.forEach { feedback.remove(it.id) }
            persist()
        }
    }

    private fun load() {
        val target = path ?: return
        if (!Files.exists(target)) return
        try {
            val parsed = storeJson.decodeFromString(LearningStoreFile.serializer(), Files.readString(target, StandardCharsets.UTF_8))
            parsed.policies.forEach { require(it.appId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))); policies[it.appId] = it }
            parsed.consents.forEach { row -> require(row.subjectHash.matches(Regex("[a-f0-9]{64}"))); consents[consentKey(row.appId, row.subjectHash)] = row }
            parsed.feedback.forEach { row -> require(row.id.matches(Regex("lf_[A-Za-z0-9]{10,40}"))); require(row.subjectHash.matches(Regex("[a-f0-9]{64}"))); feedback[row.id] = row }
            purgeExpired()
        } catch (failure: Exception) {
            throw IllegalStateException("Could not load the Vibes learning store. Back up and repair the file before restarting.", failure)
        }
    }

    private fun persist() {
        val target = path ?: return
        val absolute = target.toAbsolutePath().normalize()
        val parent = absolute.parent ?: throw IllegalStateException("Learning storage directory is missing")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "vibes-learning-", ".tmp")
        try {
            Files.writeString(temporary, storeJson.encodeToString(LearningStoreFile(policies.values.toList(), consents.values.toList(), feedback.values.toList())), StandardCharsets.UTF_8)
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING) }
            runCatching { Files.setPosixFilePermissions(absolute, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        } finally { Files.deleteIfExists(temporary) }
    }
}

internal class LearningQuotaExceeded(message: String) : IllegalArgumentException(message)
internal class LearningConsentRequired(message: String) : IllegalArgumentException(message)
