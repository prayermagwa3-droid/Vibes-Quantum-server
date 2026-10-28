package com.vibes.quantum.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class PersonalMemoryRecord(
    val id: String,
    val text: String,
    val purpose: String,
    val source: String,
    val confidence: String = "user-provided",
    val createdAt: String,
    val updatedAt: String
)
@Serializable data class SavePersonalMemoryRequest(val text: String, val purpose: String, val consent: Boolean)
@Serializable data class PersonalMemoryExport(val ownerId: String, val exportedAt: String, val records: List<PersonalMemoryRecord>)
@Serializable data class SecurityStatus(val encryptionConfigured: Boolean, val auditIntegrity: String, val auditEvents: Long, val note: String)
@Serializable data class AuditVerification(val valid: Boolean, val events: Long, val note: String)
@Serializable private data class EncryptedVault(val version: Int = 1, val iv: String, val ciphertext: String)
@Serializable private data class AuditEvent(val timestamp: String, val actor: String, val action: String, val target: String, val previousHash: String, val hash: String)

/** AES-256-GCM vault. The 32-byte key is supplied outside the data file as a 64-character hex environment secret. */
private class PersonalMemoryVault(
    private val file: Path = Paths.get(System.getenv("VIBES_PERSONAL_MEMORY_FILE") ?: "data/personal-memory.enc"),
    private val keyHex: String? = System.getenv("VIBES_PERSONAL_MEMORY_KEY_HEX")
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val lock = Any()
    private fun key(): ByteArray? {
        val raw = keyHex?.trim() ?: return null
        if (!raw.matches(Regex("[0-9a-fA-F]{64}"))) return null
        return raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    fun configured() = key() != null
    private fun readAll(): MutableMap<String, MutableList<PersonalMemoryRecord>> {
        val k = key() ?: throw IllegalStateException("Personal memory encryption key is not configured")
        if (!Files.exists(file)) return mutableMapOf()
        val blob = json.decodeFromString<EncryptedVault>(Files.readString(file))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, Base64.getDecoder().decode(blob.iv)))
        cipher.updateAAD("vibes-personal-memory-v1".toByteArray(StandardCharsets.UTF_8))
        val plain = cipher.doFinal(Base64.getDecoder().decode(blob.ciphertext)).toString(StandardCharsets.UTF_8)
        return json.decodeFromString<Map<String, List<PersonalMemoryRecord>>>(plain).mapValues { it.value.toMutableList() }.toMutableMap()
    }
    private fun writeAll(data: Map<String, List<PersonalMemoryRecord>>) {
        val k = key() ?: throw IllegalStateException("Personal memory encryption key is not configured")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("vibes-personal-memory-v1".toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(json.encodeToString(data).toByteArray(StandardCharsets.UTF_8))
        val blob = json.encodeToString(EncryptedVault(iv = Base64.getEncoder().encodeToString(iv), ciphertext = Base64.getEncoder().encodeToString(encrypted)))
        file.parent?.let { Files.createDirectories(it) }
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(temp, blob)
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
    }
    fun list(owner: String) = synchronized(lock) { readAll()[owner]?.toList() ?: emptyList() }
    fun save(owner: String, request: SavePersonalMemoryRequest): PersonalMemoryRecord = synchronized(lock) {
        require(request.consent) { "Explicit consent is required" }
        require(request.text.isNotBlank() && request.text.length <= 1200) { "Memory text must be 1–1200 characters" }
        require(request.purpose in setOf("response_preference", "active_project", "learning_goal")) { "Unsupported memory purpose" }
        val all = readAll(); val now = Instant.now().toString()
        val record = PersonalMemoryRecord(java.util.UUID.randomUUID().toString(), request.text.trim(), request.purpose, "explicit-user-consent", createdAt = now, updatedAt = now)
        val owned = all.getOrPut(owner) { mutableListOf() }; owned.add(record); writeAll(all); record
    }
    fun delete(owner: String, id: String): Boolean = synchronized(lock) {
        val all = readAll(); val owned = all[owner] ?: return@synchronized false
        val changed = owned.removeIf { it.id == id }; if (changed) writeAll(all); changed
    }
    fun export(owner: String) = PersonalMemoryExport(owner, Instant.now().toString(), list(owner))
}

/** Append-only hash-chained event log. Hash chaining detects edits; it is not distributed consensus or tamper-proof. */
private class IntegrityAuditLog(private val file: Path = Paths.get(System.getenv("VIBES_SECURITY_AUDIT_FILE") ?: "data/security-audit.jsonl")) {
    private val lock = Any()
    private val json = Json { encodeDefaults = true }
    private fun digest(raw: String) = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun append(actor: String, action: String, target: String) = synchronized(lock) {
        file.parent?.let { Files.createDirectories(it) }
        val rows = if (Files.exists(file)) Files.readAllLines(file).filter { it.isNotBlank() } else emptyList()
        val previous = rows.lastOrNull()?.let { runCatching { json.decodeFromString<AuditEvent>(it).hash }.getOrNull() } ?: "GENESIS"
        val timestamp = Instant.now().toString()
        val safeActor = actor.take(120); val safeAction = action.take(100); val safeTarget = target.replace(Regex("(?i)(token|password|secret|api[_-]?key)=?[^&\\s]*"), "\$1=[REDACTED]").take(180)
        val body = "$timestamp|$safeActor|$safeAction|$safeTarget|$previous"
        val event = AuditEvent(timestamp, safeActor, safeAction, safeTarget, previous, digest(body))
        Files.writeString(file, json.encodeToString(event) + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }
    fun verify(): AuditVerification = synchronized(lock) {
        if (!Files.exists(file)) return@synchronized AuditVerification(true, 0, "No events recorded yet; absence is not proof of integrity history.")
        var prev = "GENESIS"; var count = 0L
        try {
            Files.readAllLines(file).filter { it.isNotBlank() }.forEach { row ->
                val e = json.decodeFromString<AuditEvent>(row)
                if (e.previousHash != prev) return@synchronized AuditVerification(false, count, "Audit chain link mismatch at event ${count + 1}.")
                val body = "${e.timestamp}|${e.actor}|${e.action}|${e.target}|${e.previousHash}"
                if (digest(body) != e.hash) return@synchronized AuditVerification(false, count, "Audit event digest mismatch at event ${count + 1}.")
                prev = e.hash; count++
            }
            AuditVerification(true, count, "Hash chain validates. External log protection is still required against full-file replacement.")
        } catch (_: Exception) { AuditVerification(false, count, "Audit log is unreadable or malformed; preserve the file for incident review.") }
    }
}

fun Route.securityAndPersonalMemoryRoutes(resolveUserId: (String) -> String?) {
    val vault = PersonalMemoryVault()
    val audit = IntegrityAuditLog()
    route("/api/v1/security") {
        get("/status") {
            val v = audit.verify()
            call.respond(SecurityStatus(vault.configured(), if (!v.valid) "FAILED" else if (v.events == 0L) "NO_EVENTS" else "VALID", v.events,
                if (!v.valid) "Audit integrity warning: preserve logs and investigate." else "Hash-chain check only; configure protected backups and external log shipping."))
        }
        get("/audit/verify") { call.respond(audit.verify()) }
    }
    route("/api/v1/personal-memory") {
        fun user(call: ApplicationCall): String? {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() } ?: return null
            return resolveUserId(token)
        }
        get {
            val owner = user(call) ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            if (!vault.configured()) return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Personal memory encryption is not configured"))
            runCatching { vault.list(owner) }.onSuccess { call.respond(it) }.onFailure { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Memory unavailable; data was not reset")) }
        }
        post {
            val owner = user(call) ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            if (!vault.configured()) return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Personal memory encryption is not configured"))
            val req = runCatching { call.receive<SavePersonalMemoryRequest>() }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid memory request")) }
            runCatching { vault.save(owner, req) }.onSuccess { audit.append(owner, "personal_memory_saved", it.id); call.respond(HttpStatusCode.Created, it) }
                .onFailure { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Memory was not saved"))) }
        }
        get("/export") {
            val owner = user(call) ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            if (!vault.configured()) return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Personal memory encryption is not configured"))
            runCatching { vault.export(owner) }.onSuccess { audit.append(owner, "personal_memory_exported", "count=${it.records.size}"); call.respond(it) }
                .onFailure { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Memory export unavailable; data was not reset")) }
        }
        delete("/{id}") {
            val owner = user(call) ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            if (!vault.configured()) return@delete call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Personal memory encryption is not configured"))
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Memory ID required"))
            runCatching { vault.delete(owner, id) }.onSuccess { removed -> if (removed) audit.append(owner, "personal_memory_deleted", id); call.respond(mapOf("deleted" to removed, "recoverable" to false, "note" to "Encrypted vault backup retention must be configured separately.")) }
                .onFailure { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Memory deletion unavailable; data was not reset")) }
        }
    }
}
