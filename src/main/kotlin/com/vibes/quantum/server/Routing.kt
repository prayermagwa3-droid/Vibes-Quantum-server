package com.vibes.quantum.server
import com.vibes.quantum.server.sources.BraveSearchConnector
import com.vibes.quantum.server.sources.ConnectorHttpClient
import com.vibes.quantum.server.sources.SourceCandidateStore
import com.vibes.quantum.server.sources.SourceConnectorService
import com.vibes.quantum.server.sources.RedditDiscussionConnector
import com.vibes.quantum.server.sources.WikipediaSourceConnector
import com.vibes.quantum.server.sources.YouTubeMetadataConnector
import com.vibes.quantum.server.sources.sourceConnectorRoutes
import com.vibes.quantum.server.curriculum.CurriculumService
import com.vibes.quantum.server.curriculum.CurriculumStore
import com.vibes.quantum.server.curriculum.curriculumRoutes
import com.vibes.quantum.server.learning.LearningStore
import com.vibes.quantum.server.learning.LearningKeyAuthorizer
import com.vibes.quantum.server.learning.learningRoutes
import com.vibes.quantum.server.ai.ProviderRouter
import com.vibes.quantum.server.ai.CouncilService
import com.vibes.quantum.server.ai.LanguageCatalog
import com.vibes.quantum.server.ai.qualityLanguageRoutes
import com.vibes.quantum.server.ai.AdminReviewQueue
import com.vibes.quantum.server.ai.ApprovedKnowledgeRetriever
import com.vibes.quantum.server.ai.councilAndAgentRoutes
import com.vibes.quantum.server.agents.AgentOrchestrator
import com.vibes.quantum.server.agents.ContainerSandboxExecutor
import com.vibes.quantum.server.agents.DisabledSandboxExecutor
import com.vibes.quantum.server.agents.WorkspaceAuthorization
import com.vibes.quantum.server.agents.WorkspaceEngineeringService
import com.vibes.quantum.server.agents.workspaceEngineeringRoutes
import com.vibes.quantum.server.quality.QualityGateService
import com.vibes.quantum.server.quality.MemoryLifecycleService
import com.vibes.quantum.server.quality.qualityMemoryRoutes
import com.vibes.quantum.server.memory.UnifiedMemoryIndex
import com.vibes.quantum.server.memory.unifiedMemoryRoutes
import com.vibes.quantum.server.qualityMetadataRoutes
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.PayloadTooLargeException

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.routing.routing
import io.ktor.server.routing.intercept
import io.ktor.server.routing.route
import io.ktor.server.application.install
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpConnectTimeoutException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

private const val GATEWAY_VERSION = "2.6.0"
private const val PUBLIC_BASE_URL = "https://vibesquantum.duckdns.org"
private const val OFFLINE_AFTER_SECONDS = 300L
private const val MAX_PAGE_SIZE = 100
private const val MUTATION_LIMIT_PER_MINUTE = 60
private const val MAX_REQUEST_BODY_BYTES = 65_536
private const val MAX_CHAT_MESSAGE_CHARS = 32_000
private const val MAX_CHAT_TOTAL_CHARS = 60_000
private const val MAX_COMPLETION_TOKENS = 4_096
private const val MAX_UPSTREAM_RESPONSE_BYTES = 2 * 1024 * 1024
private const val CHAT_LIMIT_PER_API_KEY_PER_MINUTE = 20
private const val MIN_GATEWAY_ADMIN_KEY_LENGTH = 32
private val processStartedAt = Instant.now()
private val CORRELATION_ID_ATTRIBUTE = AttributeKey<String>("VibesCorrelationId")
private val registryJson = Json { prettyPrint = true; ignoreUnknownKeys = false }

@Serializable
data class VibesAppRegistration(
    val appId: String,
    val displayName: String,
    val baseUrl: String,
    val capabilities: List<String> = emptyList(),
    val version: String = "1.0.0"
)

@Serializable
data class VibesAppRecord(
    val appId: String,
    val displayName: String,
    val baseUrl: String,
    val capabilities: List<String>,
    val version: String,
    val status: String,
    val registeredAt: String,
    val lastSeenAt: String
)

@Serializable
data class ApiError(val error: String, val hint: String? = null)

@Serializable
data class HealthResponse(
    val name: String,
    val assistant: String,
    val status: String,
    val service: String,
    val gateway: String,
    val timestamp: String,
    val uptimeSeconds: Long,
    val registeredApps: Int,
    val registrationEnabled: Boolean,
    val registryMode: String,
    val securityWarnings: List<String>
)

/** Readiness is separate from liveness; a live process can still have an unsafe admin-key configuration. */
@Serializable
data class GatewayReadinessResponse(
    val ready: Boolean,
    val status: String,
    val timestamp: String,
    val checks: Map<String, Boolean>,
    val securityWarnings: List<String>
)

@Serializable
data class ApiIndexResponse(val name: String, val version: String, val gateway: String, val endpoints: List<String>)

@Serializable
data class GatewayInfoResponse(
    val name: String,
    val version: String,
    val purpose: String,
    val registrationEnabled: Boolean,
    val authentication: String,
    val supportedTransports: List<String>,
    val registry: String,
    val offlineAfterSeconds: Long,
    val mutationLimitPerMinute: Int,
    val features: List<String>,
    val publicBaseUrl: String = PUBLIC_BASE_URL
)

@Serializable
data class AppSearchFilters(val q: String? = null, val capability: String? = null, val status: String? = null)

@Serializable
data class AppListResponse(
    val count: Int,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val apps: List<VibesAppRecord>,
    val filters: AppSearchFilters
)

@Serializable
data class GatewayStatsResponse(
    val generatedAt: String,
    val uptimeSeconds: Long,
    val registrationEnabled: Boolean,
    val totalApps: Int,
    val onlineApps: Int,
    val offlineApps: Int,
    val capabilities: Map<String, Int>
)

@Serializable
data class AppRemovalResponse(val removed: Boolean, val appId: String)

@Serializable
data class GoogleSignInRequest(val idToken: String? = null, val credential: String? = null)

@Serializable
data class AuthUser(
    val id: String,
    val email: String,
    val name: String? = null,
    val picture: String? = null,
    val provider: String = "google"
)

@Serializable
data class GoogleSignInConfigResponse(val enabled: Boolean, val clientId: String? = null, val provider: String = "google")

@Serializable
data class GoogleSignInResponse(
    val authenticated: Boolean = true,
    val tokenType: String = "Bearer",
    val accessToken: String,
    val expiresAt: String,
    val user: AuthUser
)

@Serializable
data class CurrentUserResponse(val authenticated: Boolean = true, val expiresAt: String, val user: AuthUser)

@Serializable
data class LogoutResponse(val loggedOut: Boolean = true)

@Serializable
data class ApiKeyCreateRequest(
    val name: String? = null,
    val appId: String? = null,
    val scopes: List<String> = listOf("apps:register", "apps:heartbeat")
)

@Serializable
data class ApiKeyMetadata(
    val id: String,
    val name: String,
    val appId: String,
    val keyPrefix: String,
    val scopes: List<String>,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val revokedAt: String? = null
)

@Serializable
data class ApiKeyCreateResponse(val apiKey: String, val key: ApiKeyMetadata, val warning: String = "Copy this key now. It will not be shown again.")

@Serializable
data class ApiKeyListResponse(val keys: List<ApiKeyMetadata>, val count: Int)

@Serializable
data class ApiKeyRevocationResponse(val revoked: Boolean, val id: String)

@Serializable
data class GatewayAiStatusResponse(
    val enabled: Boolean,
    val mode: String = "openai-compatible",
    val defaultModel: String,
    val models: List<String>,
    val chatCompletionsPath: String = "/v1/chat/completions",
    val modelsPath: String = "/v1/models",
    val streamingSupported: Boolean = true,
    val maxInputCharacters: Int = MAX_CHAT_TOTAL_CHARS,
    val maxOutputTokens: Int = MAX_COMPLETION_TOKENS,
    val note: String = "Provider credentials remain server-side. Use an app-bound API key with ai:chat or ai:models scope."
)

@Serializable
data class GatewayModel(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0L,
    val owned_by: String = "vibes-quantum"
)

@Serializable
data class GatewayModelList(val `object`: String = "list", val data: List<GatewayModel>)

@Serializable
data class AppUsageSummary(
    val appId: String,
    val requests: Long,
    val successfulRequests: Long,
    val failedRequests: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val lastRequestAt: String?
)

@Serializable
data class GatewayUsageResponse(
    val generatedAt: String,
    val totalRequests: Long,
    val successfulRequests: Long,
    val failedRequests: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val apps: List<AppUsageSummary>,
    val note: String = "In-memory counters only; prompts and response text are not retained and counters reset when the server restarts."
)

@Serializable
private data class PersistedGatewayApiKey(
    val id: String,
    val name: String,
    val appId: String,
    val tokenHash: String,
    val keyPrefix: String,
    val scopes: List<String>,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val revokedAt: String? = null
)

private data class AuthSession(val user: AuthUser, val expiresAt: Instant)
private data class ApiKeyPrincipal(val id: String, val appId: String, val scopes: List<String>)

private class GatewayUsageRecorder {
    private data class Counter(
        var requests: Long = 0,
        var successful: Long = 0,
        var failed: Long = 0,
        var promptTokens: Long = 0,
        var completionTokens: Long = 0,
        var lastRequestAt: String? = null
    )
    private val byApp = linkedMapOf<String, Counter>()

    @Synchronized
    fun record(appId: String, success: Boolean, promptTokens: Long = 0, completionTokens: Long = 0) {
        val current = byApp.getOrPut(appId) { Counter() }
        current.requests += 1
        if (success) current.successful += 1 else current.failed += 1
        current.promptTokens += promptTokens.coerceAtLeast(0)
        current.completionTokens += completionTokens.coerceAtLeast(0)
        current.lastRequestAt = Instant.now().toString()
    }

    @Synchronized
    fun snapshot(): GatewayUsageResponse {
        val apps = byApp.entries.sortedBy { it.key }.map { (appId, value) ->
            AppUsageSummary(appId, value.requests, value.successful, value.failed, value.promptTokens, value.completionTokens, value.lastRequestAt)
        }
        return GatewayUsageResponse(
            generatedAt = Instant.now().toString(),
            totalRequests = apps.sumOf { it.requests },
            successfulRequests = apps.sumOf { it.successfulRequests },
            failedRequests = apps.sumOf { it.failedRequests },
            promptTokens = apps.sumOf { it.promptTokens },
            completionTokens = apps.sumOf { it.completionTokens },
            apps = apps
        )
    }
}

private class InvalidChatRequest(message: String) : RuntimeException(message)
internal data class NormalizedChatRequest(val model: String, val body: String, val promptCharacters: Int, val streaming: Boolean)

private class InvalidGoogleToken : RuntimeException()
private class GoogleVerificationUnavailable(cause: Throwable? = null) : RuntimeException(cause)

/** Google signing keys are fetched over TLS and cached according to Google's Cache-Control header. */
private object GoogleSigningKeys {
    private const val CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs"
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val lock = Any()
    @Volatile private var cached: Map<String, PublicKey> = emptyMap()
    @Volatile private var expiresAtMillis: Long = 0L
    @Volatile private var lastRefreshAtMillis: Long = 0L
    private val jwksJson = Json { ignoreUnknownKeys = true }

    fun get(kid: String): PublicKey {
        var keys = cached
        var now = System.currentTimeMillis()
        val keyMissingAndRefreshAllowed = kid !in keys && now - lastRefreshAtMillis >= 60_000L
        if (now >= expiresAtMillis || keyMissingAndRefreshAllowed) {
            synchronized(lock) {
                keys = cached
                now = System.currentTimeMillis()
                val shouldRefreshMissingKey = kid !in keys && now - lastRefreshAtMillis >= 60_000L
                if (now >= expiresAtMillis || shouldRefreshMissingKey) refresh()
                keys = cached
            }
        }
        return keys[kid] ?: throw InvalidGoogleToken()
    }

    private fun refresh() {
        try {
            val request = HttpRequest.newBuilder(URI(CERTS_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200 || response.body().length > 256_000) {
                throw GoogleVerificationUnavailable()
            }
            val root = jwksJson.parseToJsonElement(response.body()).jsonObject
            val values = root["keys"]?.jsonArray ?: throw GoogleVerificationUnavailable()
            val parsed = linkedMapOf<String, PublicKey>()
            for (item in values) {
                val jwk = item as? JsonObject ?: continue
                if (jwk["kty"]?.jsonPrimitive?.contentOrNull != "RSA" || jwk["alg"]?.jsonPrimitive?.contentOrNull?.let { it != "RS256" } == true) continue
                if (jwk["use"]?.jsonPrimitive?.contentOrNull?.let { it != "sig" } == true) continue
                val kid = jwk["kid"]?.jsonPrimitive?.contentOrNull ?: continue
                val modulus = jwk["n"]?.jsonPrimitive?.contentOrNull ?: continue
                val exponent = jwk["e"]?.jsonPrimitive?.contentOrNull ?: continue
                try {
                    val n = BigInteger(1, Base64.getUrlDecoder().decode(modulus))
                    val e = BigInteger(1, Base64.getUrlDecoder().decode(exponent))
                    parsed[kid] = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
                } catch (_: Exception) { /* Ignore malformed keys and keep processing the trusted key set. */ }
            }
            if (parsed.isEmpty()) throw GoogleVerificationUnavailable()
            cached = parsed
            val cacheHeader = response.headers().firstValue("Cache-Control").orElse("")
            val maxAge = Regex("""(?:^|[,\s])max-age=(\d+)""", RegexOption.IGNORE_CASE)
                .find(cacheHeader)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 300L
            lastRefreshAtMillis = System.currentTimeMillis()
            expiresAtMillis = lastRefreshAtMillis + maxAge.coerceIn(60L, 3600L) * 1000L
        } catch (failure: GoogleVerificationUnavailable) {
            throw failure
        } catch (failure: Exception) {
            throw GoogleVerificationUnavailable(failure)
        }
    }
}


/** Validate a Google ID token's signature and standard claims; never trust browser-supplied profile fields. */
private fun verifyGoogleIdToken(idToken: String, clientId: String): AuthUser {
    if (idToken.length !in 100..16_384 || idToken.any { it.isWhitespace() }) throw InvalidGoogleToken()
    val pieces = idToken.split('.')
    if (pieces.size != 3) throw InvalidGoogleToken()
    try {
        val decoder = Base64.getUrlDecoder()
        val header = Json.parseToJsonElement(String(decoder.decode(pieces[0]), StandardCharsets.UTF_8)).jsonObject
        val payload = Json.parseToJsonElement(String(decoder.decode(pieces[1]), StandardCharsets.UTF_8)).jsonObject
        if (header["alg"]?.let { (it as? JsonPrimitive)?.contentOrNull } != "RS256") throw InvalidGoogleToken()
        val kid = header["kid"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: throw InvalidGoogleToken()
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(GoogleSigningKeys.get(kid))
        signature.update("${pieces[0]}.${pieces[1]}".toByteArray(StandardCharsets.US_ASCII))
        if (!signature.verify(decoder.decode(pieces[2]))) throw InvalidGoogleToken()

        val issuer = payload["iss"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (issuer !in setOf("accounts.google.com", "https://accounts.google.com")) throw InvalidGoogleToken()
        val audience = payload["aud"]
        val audienceMatches = when (audience) {
            is JsonPrimitive -> audience.contentOrNull == clientId
            is JsonArray -> audience.any { (it as? JsonPrimitive)?.contentOrNull == clientId }
            else -> false
        }
        if (!audienceMatches) throw InvalidGoogleToken()
        val audienceCount = (audience as? JsonArray)?.size ?: 1
        val authorizedParty = payload["azp"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (authorizedParty != null && authorizedParty != clientId) throw InvalidGoogleToken()
        if (audienceCount > 1 && authorizedParty != clientId) throw InvalidGoogleToken()

        val now = Instant.now().epochSecond
        val expiry = payload["exp"]?.let { (it as? JsonPrimitive)?.longOrNull } ?: throw InvalidGoogleToken()
        val issued = payload["iat"]?.let { (it as? JsonPrimitive)?.longOrNull } ?: throw InvalidGoogleToken()
        if (expiry <= now || issued > now + 300 || issued > expiry || expiry - issued > 86_400) throw InvalidGoogleToken()
        val sub = payload["sub"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() && it.length <= 255 } ?: throw InvalidGoogleToken()
        val email = payload["email"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.length <= 320 && it.contains('@') } ?: throw InvalidGoogleToken()
        val emailVerified = payload["email_verified"]?.let { claim ->
            when (claim) {
                is JsonPrimitive -> claim.booleanOrNull ?: (claim.contentOrNull == "true")
                else -> false
            }
        } ?: false
        if (!emailVerified) throw InvalidGoogleToken()

        val name = payload["name"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() && it.length <= 200 && it.none { ch -> ch.isISOControl() } }
        val picture = payload["picture"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { value -> value.length <= 2_048 && runCatching { URI(value).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false) }
        return AuthUser(id = sub, email = email, name = name, picture = picture)
    } catch (failure: InvalidGoogleToken) {
        throw failure
    } catch (failure: GoogleVerificationUnavailable) {
        throw failure
    } catch (_: Exception) {
        throw InvalidGoogleToken()
    }
}

private val sessionRandom = SecureRandom()
private fun newSessionToken(): String {
    val bytes = ByteArray(32)
    sessionRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
private fun tokenDigest(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
private fun ApplicationCall.bearerToken(): String? {
    val header = request.header("Authorization") ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substringAfter(' ').trim().takeIf { it.length in 40..128 && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' } }
}

private class RegistryPersistenceException(cause: Throwable) : RuntimeException(cause)

/** Small file-backed registry. Writes are atomic where supported and failed writes are rolled back in memory. */
private class AppRegistry(fileName: String?) {
    private val path: Path? = fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
    private val records = linkedMapOf<String, VibesAppRecord>()
    val isPersistent: Boolean get() = path != null

    init { load() }

    @Synchronized
    fun snapshot(): List<VibesAppRecord> = records.values.toList()

    @Synchronized
    operator fun get(id: String): VibesAppRecord? = records[id]

    @Synchronized
    fun upsert(input: VibesAppRegistration, capabilities: List<String>, now: String): Pair<VibesAppRecord, Boolean> {
        val previous = records[input.appId]
        val saved = VibesAppRecord(
            appId = input.appId,
            displayName = input.displayName.trim(),
            baseUrl = input.baseUrl.trim().trimEnd('/'),
            capabilities = capabilities,
            version = input.version.trim(),
            status = "online",
            registeredAt = previous?.registeredAt ?: now,
            lastSeenAt = now
        )
        records[input.appId] = saved
        try {
            persist()
        } catch (failure: RegistryPersistenceException) {
            if (previous == null) records.remove(input.appId) else records[input.appId] = previous
            throw failure
        }
        return saved to (previous == null)
    }

    @Synchronized
    fun heartbeat(id: String, now: String): VibesAppRecord? {
        val previous = records[id] ?: return null
        val updated = previous.copy(status = "online", lastSeenAt = now)
        records[id] = updated
        try {
            persist()
        } catch (failure: RegistryPersistenceException) {
            records[id] = previous
            throw failure
        }
        return updated
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val previous = records.remove(id) ?: return false
        try {
            persist()
        } catch (failure: RegistryPersistenceException) {
            records[id] = previous
            throw failure
        }
        return true
    }

    private fun load() {
        val target = path ?: return
        if (!Files.exists(target)) return
        try {
            val entries = registryJson.decodeFromString(ListSerializer(VibesAppRecord.serializer()), Files.readString(target, StandardCharsets.UTF_8))
            entries.forEach { entry ->
                if (!validId(entry.appId)) throw IllegalStateException("Invalid app identifier in saved registry")
                records[entry.appId] = entry
            }
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Could not load the saved Vibes Quantum registry. Back up and repair the registry file before restarting.",
                failure
            )
        }
    }

    private fun persist() {
        val target = path ?: return
        try {
            val absolute = target.toAbsolutePath().normalize()
            val parent = absolute.parent ?: throw IllegalStateException("Registry directory is missing")
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, "vibes-registry-", ".tmp")
            try {
                val payload = registryJson.encodeToString(
                    ListSerializer(VibesAppRecord.serializer()),
                    records.values.sortedBy { it.appId.lowercase() }
                )
                Files.writeString(temporary, payload, StandardCharsets.UTF_8)
                try {
                    Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (failure: Exception) {
            throw RegistryPersistenceException(failure)
        }
    }
}

/**
 * File-backed app-key store. Only SHA-256 digests are persisted; plaintext keys are returned once at creation.
 * Keys are bound to one app ID and only grant the requested narrow registration/heartbeat scopes.
 */
private class GatewayApiKeyStore(fileName: String?) {
    private val path: Path? = fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
    private val records = linkedMapOf<String, PersistedGatewayApiKey>()
    val isPersistent: Boolean get() = path != null

    init { load() }

    @Synchronized
    fun list(): List<ApiKeyMetadata> = records.values.sortedByDescending { it.createdAt }.map { it.metadata() }

    @Synchronized
    fun create(name: String, appId: String, scopes: List<String>): Pair<String, ApiKeyMetadata> {
        val tokenBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = "vq_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val id = "key_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)
        val now = Instant.now().toString()
        val entry = PersistedGatewayApiKey(
            id = id, name = name.trim(), appId = appId, tokenHash = tokenDigest(token),
            keyPrefix = token.take(16), scopes = scopes.distinct().sorted(), createdAt = now
        )
        records[id] = entry
        try { persist() } catch (failure: Exception) { records.remove(id); throw failure }
        return token to entry.metadata()
    }

    @Synchronized
    fun revoke(id: String): Boolean {
        val previous = records[id] ?: return false
        if (previous.revokedAt != null) return false
        records[id] = previous.copy(revokedAt = Instant.now().toString())
        try { persist() } catch (failure: Exception) { records[id] = previous; throw failure }
        return true
    }

    @Synchronized
    fun authenticate(token: String?): ApiKeyPrincipal? {
        if (token.isNullOrBlank() || token.length !in 30..128) return null
        val digest = tokenDigest(token)
        val match = records.values.firstOrNull { entry ->
            entry.revokedAt == null && MessageDigest.isEqual(
                entry.tokenHash.toByteArray(StandardCharsets.US_ASCII), digest.toByteArray(StandardCharsets.US_ASCII)
            )
        } ?: return null
        // Avoid a disk write on every inference request while still keeping lastUsedAt useful.
        val lastUsed = match.lastUsedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (lastUsed == null || Duration.between(lastUsed, Instant.now()).seconds >= 60) {
            val updated = match.copy(lastUsedAt = Instant.now().toString())
            records[match.id] = updated
            try { persist() } catch (_: Exception) { records[match.id] = match }
        }
        return ApiKeyPrincipal(match.id, match.appId, match.scopes)
    }

    @Synchronized
    fun authorize(token: String?, requiredScope: String, appId: String): Boolean {
        if (!validId(appId)) return false
        val principal = authenticate(token) ?: return false
        return principal.appId == appId && requiredScope in principal.scopes
    }

    private fun PersistedGatewayApiKey.metadata() = ApiKeyMetadata(
        id = id, name = name, appId = appId, keyPrefix = keyPrefix, scopes = scopes,
        createdAt = createdAt, lastUsedAt = lastUsedAt, revokedAt = revokedAt
    )

    private fun load() {
        val target = path ?: return
        if (!Files.exists(target)) return
        try {
            val loaded = registryJson.decodeFromString(
                ListSerializer(PersistedGatewayApiKey.serializer()), Files.readString(target, StandardCharsets.UTF_8)
            )
            loaded.forEach { entry ->
                require(entry.id.matches(Regex("key_[A-Za-z0-9]{10,40}")))
                require(validId(entry.appId))
                require(entry.tokenHash.matches(Regex("[a-f0-9]{64}")))
                require(entry.scopes.all { it in ALLOWED_APP_KEY_SCOPES })
                records[entry.id] = entry
            }
        } catch (failure: Exception) {
            throw IllegalStateException("Could not load the Vibes Quantum API key store. Back up and repair the key file before restarting.", failure)
        }
    }

    private fun persist() {
        val target = path ?: return
        val absolute = target.toAbsolutePath().normalize()
        val parent = absolute.parent ?: throw IllegalStateException("API key storage directory is missing")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "vibes-api-keys-", ".tmp")
        try {
            Files.writeString(temporary, registryJson.encodeToString(
                ListSerializer(PersistedGatewayApiKey.serializer()), records.values.toList()
            ), StandardCharsets.UTF_8)
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING) }
            runCatching { Files.setPosixFilePermissions(absolute, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        val ALLOWED_APP_KEY_SCOPES = setOf("apps:register", "apps:heartbeat", "ai:chat", "ai:models", "learning:feedback", "agents:run", "workspace:run", "workspace:continuous")
    }
}

/** A bounded, in-memory per-client limiter for registry-changing requests. */
class MutationRateLimiter(
    private val limit: Int = MUTATION_LIMIT_PER_MINUTE,
    private val windowMillis: Long = 60_000L,
    private val trustedProxies: Set<String> = emptySet()
) {
    private data class Bucket(val startedAt: Long, val count: Int)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** Bucket key for this request. Behind a configured reverse proxy this is the real client, not the proxy. */
    fun clientKey(call: ApplicationCall): String = resolveClientAddress(
        peer = runCatching { call.request.local.remoteHost }.getOrDefault("unknown"),
        forwardedFor = call.request.header("X-Forwarded-For"),
        trustedProxies = trustedProxies
    )

    fun allow(client: String, now: Long = System.currentTimeMillis()): Boolean {
        val bucket = buckets.compute(client) { _, existing ->
            if (existing == null || now - existing.startedAt >= windowMillis || now < existing.startedAt) Bucket(now, 1)
            else existing.copy(count = existing.count + 1)
        } ?: Bucket(now, 1)
        if (buckets.size > 1_000) buckets.entries.removeIf { now - it.value.startedAt > windowMillis * 2 }
        return bucket.count <= limit
    }
}

/**
 * Chooses the address that rate limits are keyed on. X-Forwarded-For is trusted only when the direct
 * peer is a configured proxy, so a client cannot pick its own bucket by sending a spoofed header.
 * Among the forwarded hops, the right-most one that is not a trusted proxy is the client.
 */
internal fun resolveClientAddress(peer: String, forwardedFor: String?, trustedProxies: Set<String>): String {
    val cleanPeer = peer.trim().take(120).ifEmpty { "unknown" }
    if (cleanPeer !in trustedProxies) return cleanPeer
    val hops = forwardedFor?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it.length <= 64 }
        ?: return cleanPeer
    for (hop in hops.asReversed()) {
        if (hop !in trustedProxies) return hop
    }
    return cleanPeer
}

/** Parses VIBES_TRUSTED_PROXIES-style comma-separated addresses. Empty input means no trusted proxies. */
internal fun parseTrustedProxies(raw: String?): Set<String> =
    raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it.length <= 64 }?.toSet().orEmpty()

private fun validId(value: String): Boolean =
    value.length in 2..64 && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))

private fun validBaseUrl(value: String): Boolean {
    if (value.isBlank() || value.length > 2_048 || value.any { it.isISOControl() } || value != value.trim()) return false
    return try {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        val normalizedHost = host?.lowercase()
        val isLocalHttp = scheme == "http" && (normalizedHost == "localhost" || normalizedHost == "127.0.0.1" || normalizedHost == "[::1]")
        (scheme == "https" || isLocalHttp) && !host.isNullOrBlank() && uri.isAbsolute &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)
    } catch (_: Exception) { false }
}

private fun safeText(value: String, maxLength: Int): Boolean =
    value.isNotBlank() && value.length <= maxLength && value.none { it.isISOControl() }

private fun authorized(provided: String?, expected: String?): Boolean {
    if (provided.isNullOrBlank() || expected.isNullOrBlank() || provided.length > 512) return false
    return MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
}

private fun effectiveRecord(record: VibesAppRecord, now: Instant = Instant.now()): VibesAppRecord {
    val lastSeen = runCatching { Instant.parse(record.lastSeenAt) }.getOrNull() ?: return record.copy(status = "offline")
    val age = runCatching { Duration.between(lastSeen, now).seconds }.getOrDefault(Long.MAX_VALUE)
    return record.copy(status = if (age >= 0 && age > OFFLINE_AFTER_SECONDS) "offline" else "online")
}

private fun uptimeSeconds(now: Instant = Instant.now()): Long =
    Duration.between(processStartedAt, now).seconds.coerceAtLeast(0)

private fun isPlaceholderConfigurationValue(value: String): Boolean {
    // Normalize separators so copied .env examples cannot accidentally mark services configured.
    val normalized = value.trim().lowercase().replace(Regex("[_\\s]+"), "-")
    return normalized in setOf("changeme", "change-me", "placeholder", "secret", "password") ||
        listOf(
            "replace-with", "replace-me", "your-gateway-key", "your-web-client-id",
            "your-provider-key", "example-secret", "placeholder"
        ).any(normalized::contains)
}

private fun isPlaceholderGatewayKey(value: String): Boolean = isPlaceholderConfigurationValue(value)

private fun hasStrongGatewayAdminKey(gatewayKey: String?): Boolean {
    val value = gatewayKey?.trim().orEmpty()
    return value.length >= MIN_GATEWAY_ADMIN_KEY_LENGTH && !isPlaceholderGatewayKey(value)
}

private fun securityWarnings(gatewayKey: String?): List<String> = buildList {
    val value = gatewayKey?.trim().orEmpty()
    when {
        value.isEmpty() -> add("Gateway administration is disabled because VIBES_GATEWAY_KEY is not configured.")
        value.length < MIN_GATEWAY_ADMIN_KEY_LENGTH -> add("Gateway administration is disabled because VIBES_GATEWAY_KEY must be at least $MIN_GATEWAY_ADMIN_KEY_LENGTH characters.")
        isPlaceholderGatewayKey(value) -> add("Gateway administration is disabled because VIBES_GATEWAY_KEY still looks like a placeholder. Generate a new random value.")
    }
}

private suspend fun ApplicationCall.checkMutationLimit(limiter: MutationRateLimiter): Boolean {
    if (!limiter.allow(limiter.clientKey(this))) {
        response.headers.append("Retry-After", "60", safeOnly = false)
        respond(HttpStatusCode.TooManyRequests, ApiError("Mutation rate limit exceeded", "Try again in about one minute"))
        return false
    }
    return true
}

internal suspend fun ApplicationCall.authorizeMutation(gatewayKey: String?, limiter: MutationRateLimiter): Boolean {
    if (!hasStrongGatewayAdminKey(gatewayKey)) {
        respond(HttpStatusCode.ServiceUnavailable, ApiError(
            "Gateway administration is disabled",
            "Set VIBES_GATEWAY_KEY to a non-placeholder random secret of at least $MIN_GATEWAY_ADMIN_KEY_LENGTH characters"
        ))
        return false
    }
    if (!checkMutationLimit(limiter)) return false
    if (!authorized(request.header("X-Vibes-Gateway-Key"), gatewayKey?.trim())) {
        respond(HttpStatusCode.Unauthorized, ApiError("Invalid gateway credentials"))
        return false
    }
    return true
}

private suspend fun ApplicationCall.authorizeAppMutation(
    gatewayKey: String?, apiKeys: GatewayApiKeyStore, limiter: MutationRateLimiter, scope: String, appId: String
): Boolean {
    if (!checkMutationLimit(limiter)) return false
    val adminHeader = request.header("X-Vibes-Gateway-Key")
    if (hasStrongGatewayAdminKey(gatewayKey) && authorized(adminHeader, gatewayKey?.trim())) return true
    val appToken = request.header("X-Vibes-API-Key")
    if (apiKeys.authorize(appToken, scope, appId)) return true
    if (!hasStrongGatewayAdminKey(gatewayKey)) {
        respond(HttpStatusCode.ServiceUnavailable, ApiError(
            "App registration is disabled",
            "Configure a random VIBES_GATEWAY_KEY of at least $MIN_GATEWAY_ADMIN_KEY_LENGTH characters and issue a scoped app key from the dashboard"
        ))
    } else {
        respond(HttpStatusCode.Unauthorized, ApiError("Invalid app credentials", "Use the app-specific X-Vibes-API-Key or the server-side X-Vibes-Gateway-Key"))
    }
    return false
}

private suspend fun ApplicationCall.authorizeWorkspaceRequest(
    gatewayKey: String?, apiKeys: GatewayApiKeyStore, limiter: MutationRateLimiter
): WorkspaceAuthorization? {
    val adminHeader = request.header("X-Vibes-Gateway-Key")
    if (hasStrongGatewayAdminKey(gatewayKey) && authorized(adminHeader, gatewayKey?.trim())) {
        if (!checkMutationLimit(limiter)) return null
        return WorkspaceAuthorization(appId = null, gatewayAdmin = true, scopes = setOf("workspace:run", "workspace:continuous"))
    }
    if (!checkMutationLimit(limiter)) return null
    val token = request.header("X-Vibes-API-Key") ?: bearerToken()
    val principal = apiKeys.authenticate(token)
    if (principal == null) {
        respond(HttpStatusCode.Unauthorized, ApiError("Workspace API key is missing or invalid", "Use a gateway admin key or an app-bound key with workspace:run scope."))
        return null
    }
    if ("workspace:run" !in principal.scopes) {
        respond(HttpStatusCode.Forbidden, ApiError("Workspace execution is not authorized", "Issue an app-bound key with workspace:run; continuous mode separately requires workspace:continuous."))
        return null
    }
    return WorkspaceAuthorization(appId = principal.appId, gatewayAdmin = false, scopes = principal.scopes.toSet())
}

private fun validModelId(value: String): Boolean =
    value.length in 1..160 && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*"))

private fun normalizeUpstreamBaseUrl(value: String?): String? {
    val raw = value?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    if (raw.length > 2_048) return null
    return try {
        val uri = URI(raw)
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            (uri.port != -1 && uri.port !in 1..65535)) null
        else uri.toASCIIString().trimEnd('/')
    } catch (_: Exception) { null }
}

internal fun normalizeChatCompletionRequest(
    raw: String,
    defaultModel: String,
    allowedModels: Set<String>,
    requestTokenBudget: Int = MAX_COMPLETION_TOKENS
): NormalizedChatRequest {
    if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_REQUEST_BODY_BYTES) throw InvalidChatRequest("Request body is too large. Keep JSON under $MAX_REQUEST_BODY_BYTES bytes.")
    val root = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
        ?: throw InvalidChatRequest("Send a valid JSON object.")
    val streaming = root["stream"]?.let { element ->
        (element as? JsonPrimitive)?.booleanOrNull
            ?: throw InvalidChatRequest("The stream field must be a boolean.")
    } ?: false
    val requestedModel = root["model"]?.let { element ->
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw InvalidChatRequest("model must be a string.")
        primitive.content
    }
    val model = (requestedModel?.takeIf { it.isNotBlank() } ?: defaultModel).let { if (it == "default") defaultModel else it }
    if (!validModelId(model) || model !in allowedModels) {
        throw InvalidChatRequest("Model is not allowed by this gateway. Choose one of the models returned by GET /v1/models.")
    }
    val rawMessages = root["messages"] as? JsonArray
        ?: throw InvalidChatRequest("messages must be a JSON array.")
    if (rawMessages.isEmpty() || rawMessages.size > 64) throw InvalidChatRequest("messages must contain between 1 and 64 items.")
    var totalChars = 0
    val normalizedMessages = rawMessages.mapIndexed { index, element ->
        val message = element as? JsonObject ?: throw InvalidChatRequest("messages[$index] must be an object.")
        val rolePrimitive = message["role"] as? JsonPrimitive
        val contentPrimitive = message["content"] as? JsonPrimitive
        if (rolePrimitive == null || !rolePrimitive.isString) throw InvalidChatRequest("messages[$index].role must be a string.")
        if (contentPrimitive == null || !contentPrimitive.isString) throw InvalidChatRequest("messages[$index].content must be plain text.")
        val role = rolePrimitive.content
        if (role !in setOf("system", "developer", "user", "assistant")) {
            throw InvalidChatRequest("messages[$index].role must be system, developer, user, or assistant.")
        }
        val content = contentPrimitive.content
        if (content.length > MAX_CHAT_MESSAGE_CHARS) throw InvalidChatRequest("A message exceeds $MAX_CHAT_MESSAGE_CHARS characters.")
        totalChars += content.length
        if (totalChars > MAX_CHAT_TOTAL_CHARS) throw InvalidChatRequest("Combined message text exceeds $MAX_CHAT_TOTAL_CHARS characters.")
        JsonObject(mapOf("role" to JsonPrimitive(role), "content" to JsonPrimitive(content)))
    }
    val output = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
        "model" to JsonPrimitive(model),
        "messages" to JsonArray(normalizedMessages),
        "stream" to JsonPrimitive(streaming)
    )
    // The gateway only forwards a small, validated parameter allow-list.
    fun boundedNumber(key: String, min: Double, max: Double) {
        val element = root[key] ?: return
        val number = (element as? JsonPrimitive)?.doubleOrNull ?: throw InvalidChatRequest("$key must be a number.")
        if (!number.isFinite() || number < min || number > max) throw InvalidChatRequest("$key must be between $min and $max.")
        output[key] = JsonPrimitive(number)
    }
    boundedNumber("temperature", 0.0, 2.0)
    boundedNumber("top_p", 0.0, 1.0)
    boundedNumber("frequency_penalty", -2.0, 2.0)
    boundedNumber("presence_penalty", -2.0, 2.0)
    val maxTokens = root["max_tokens"]
    val maxCompletionTokens = root["max_completion_tokens"]
    if (maxTokens != null && maxCompletionTokens != null) throw InvalidChatRequest("Set only one of max_tokens or max_completion_tokens.")
    val tokenElement = maxCompletionTokens ?: maxTokens
    val tokenKey = if (maxCompletionTokens != null) "max_completion_tokens" else "max_tokens"
    if (tokenElement != null) {
        val count = (tokenElement as? JsonPrimitive)?.intOrNull ?: throw InvalidChatRequest("$tokenKey must be an integer.")
        val tokenLimit = requestTokenBudget.coerceIn(1, MAX_COMPLETION_TOKENS)
        if (count !in 1..tokenLimit) throw InvalidChatRequest("$tokenKey must be between 1 and $tokenLimit for the configured request budget.")
        output[tokenKey] = JsonPrimitive(count)
    } else {
        output["max_tokens"] = JsonPrimitive(minOf(1_024, requestTokenBudget.coerceIn(1, MAX_COMPLETION_TOKENS)))
    }
    root["stop"]?.let { stop ->
        when (stop) {
            is JsonPrimitive -> if (stop.isString && stop.content.length <= 200) output["stop"] = stop else throw InvalidChatRequest("stop must be a short string or an array of strings.")
            is JsonArray -> {
                if (stop.size !in 1..4 || stop.any { it !is JsonPrimitive || !it.isString || it.content.length > 200 }) {
                    throw InvalidChatRequest("stop arrays must contain 1 to 4 short strings.")
                }
                output["stop"] = stop
            }
            else -> throw InvalidChatRequest("stop must be a short string or an array of strings.")
        }
    }
    root["seed"]?.let { seed ->
        val value = (seed as? JsonPrimitive)?.intOrNull ?: throw InvalidChatRequest("seed must be an integer.")
        output["seed"] = JsonPrimitive(value)
    }
    return NormalizedChatRequest(model, JsonObject(output).toString(), totalChars, streaming)
}

private fun extractTokenUsage(payload: String): Pair<Long, Long>? {
    val usage = runCatching { (Json.parseToJsonElement(payload) as? JsonObject)?.get("usage") as? JsonObject }.getOrNull()
        ?: return null
    val prompt = (usage["prompt_tokens"] as? JsonPrimitive)?.longOrNull
    val completion = (usage["completion_tokens"] as? JsonPrimitive)?.longOrNull
    if (prompt == null && completion == null) return null
    return (prompt ?: 0L).coerceAtLeast(0) to (completion ?: 0L).coerceAtLeast(0)
}

private data class ProviderSendResult(
    val response: HttpResponse<java.io.InputStream>,
    val latencyMs: Double,
    val retries: Int
)

private class ProviderSendFailure(
    val latencyMs: Double,
    val retries: Int,
    cause: Throwable
) : RuntimeException(cause)

private fun safeProviderSend(request: HttpRequest, operations: OperationalRuntime): ProviderSendResult {
    val started = System.nanoTime()
    var retries = 0
    while (true) {
        try {
            val response = aiUpstreamHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            return ProviderSendResult(response, (System.nanoTime() - started) / 1_000_000.0, retries)
        } catch (failure: Exception) {
            val root = generateSequence<Throwable>(failure) { it.cause }.toList()
            val definitelyPreConnect = root.any { it is ConnectException || it is UnknownHostException || it is HttpConnectTimeoutException }
            val backoff = (operations.limits.retryBackoffBaseMs * (1L shl retries.coerceAtMost(5))).coerceAtMost(30_000L)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            val withinElapsedBudget = elapsedMs + backoff < operations.limits.requestElapsedBudgetMs
            val requestRetryCap = minOf(operations.limits.maxProviderRetries, (operations.limits.requestNetworkCallBudget - 1).coerceAtLeast(0))
            if (!definitelyPreConnect || retries >= requestRetryCap || !withinElapsedBudget) {
                throw ProviderSendFailure((System.nanoTime() - started) / 1_000_000.0, retries, failure)
            }
            if (backoff > 0) {
                try { Thread.sleep(backoff) } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ProviderSendFailure((System.nanoTime() - started) / 1_000_000.0, retries, interrupted)
                }
            }
            retries++
        }
    }
}

private val aiUpstreamHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(8))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

/**
 * @param registryFile Registry JSON path. Set null for an in-memory registry (useful for tests).
 */
fun Application.configureRouting(
    trustedProxies: Set<String> = parseTrustedProxies(System.getenv("VIBES_TRUSTED_PROXIES")),
    gatewayKey: String? = System.getenv("VIBES_GATEWAY_KEY"),
    registryFile: String? = System.getenv("VIBES_REGISTRY_FILE") ?: "data/vibes-apps.json",
    apiKeysFile: String? = System.getenv("VIBES_API_KEYS_FILE") ?: "data/vibes-api-keys.json",
    upstreamBaseUrl: String? = normalizeUpstreamBaseUrl(System.getenv("VIBES_UPSTREAM_BASE_URL") ?: "https://api.openai.com/v1"),
    upstreamApiKey: String? = System.getenv("VIBES_UPSTREAM_API_KEY")?.trim()?.takeIf { it.isNotEmpty() },
    upstreamModel: String = System.getenv("VIBES_UPSTREAM_MODEL")?.trim()?.takeIf(::validModelId) ?: "unconfigured",
    allowedModels: Set<String> = (System.getenv("VIBES_ALLOWED_MODELS")?.split(',')?.map(String::trim)?.filter(::validModelId)?.toSet().orEmpty() + listOfNotNull(System.getenv("VIBES_UPSTREAM_MODEL")?.trim()?.takeIf(::validModelId))),
    googleClientId: String? = System.getenv("GOOGLE_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() },
    sourceConnectorsEnabled: Boolean = System.getenv("VIBES_SOURCE_CONNECTORS_ENABLED").equals("true", ignoreCase = true),
    sourceBraveApiKey: String? = System.getenv("VIBES_BRAVE_SEARCH_API_KEY")?.trim()?.takeIf { it.isNotEmpty() },
    sourceYouTubeApiKey: String? = System.getenv("VIBES_YOUTUBE_API_KEY")?.trim()?.takeIf { it.isNotEmpty() },
    sourceRedditClientId: String? = System.getenv("VIBES_REDDIT_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() },
    sourceRedditClientSecret: String? = System.getenv("VIBES_REDDIT_CLIENT_SECRET")?.trim()?.takeIf { it.isNotEmpty() },
    sourceStoreFile: String? = System.getenv("VIBES_SOURCE_STORE_FILE") ?: "data/vibes-source-learning.json",
    sourceMaxRequestsPerRun: Int = System.getenv("VIBES_SOURCE_MAX_REQUESTS_PER_RUN")?.toIntOrNull() ?: 8,
    sourceMaxBytesPerRun: Long = System.getenv("VIBES_SOURCE_MAX_BYTES_PER_RUN")?.toLongOrNull() ?: 524_288L,
    sourceMaxCandidatesPerRun: Int = System.getenv("VIBES_SOURCE_MAX_CANDIDATES_PER_RUN")?.toIntOrNull() ?: 15,
    curriculumStoreFile: String? = System.getenv("VIBES_CURRICULUM_STORE_FILE") ?: "data/vibes-curriculum.json",
    learningStoreFile: String? = System.getenv("VIBES_LEARNING_STORE_FILE") ?: "data/vibes-learning.json"
) {
    val adminKey = gatewayKey?.trim()?.takeIf { it.isNotEmpty() }
    val safeGoogleClientId = googleClientId?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val safeUpstreamApiKey = upstreamApiKey?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val safeUpstreamModel = upstreamModel.trim().takeIf(::validModelId) ?: "unconfigured"
    val registry = AppRegistry(registryFile)
    val apiKeys = GatewayApiKeyStore(apiKeysFile)
    val mutationLimiter = MutationRateLimiter(trustedProxies = trustedProxies)
    val authLimiter = MutationRateLimiter(limit = 10, trustedProxies = trustedProxies)
    val operations = OperationalRuntime()
    val chatLimiter = MutationRateLimiter(limit = operations.limits.perApiKeyRequestsPerMinute)
    val appChatLimiter = MutationRateLimiter(limit = operations.limits.perAppRequestsPerMinute)
    val usageRecorder = GatewayUsageRecorder()
    // Re-validate explicit overrides too, not only environment-backed defaults.
    val safeUpstreamBaseUrl = normalizeUpstreamBaseUrl(upstreamBaseUrl)
    val sessions = ConcurrentHashMap<String, AuthSession>()
    val modelAllowlist = (allowedModels + safeUpstreamModel).filter { validModelId(it) && it != "unconfigured" }.toSortedSet()
    val safeBraveKey = sourceBraveApiKey?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val safeYouTubeKey = sourceYouTubeApiKey?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val safeRedditClientId = sourceRedditClientId?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val safeRedditClientSecret = sourceRedditClientSecret?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val curriculumStore = CurriculumStore(curriculumStoreFile)
    val curriculumService = CurriculumService(curriculumStore)
    val learningStore = LearningStore(learningStoreFile)
    val providerRouter = ProviderRouter.fromEnvironment(
        legacyBaseUrl = safeUpstreamBaseUrl,
        legacyApiKey = safeUpstreamApiKey,
        legacyModel = safeUpstreamModel
    )
    val reviewQueue = AdminReviewQueue()
    val sandboxImage = System.getenv("VIBES_AGENT_SANDBOX_IMAGE")?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderConfigurationValue(it) }
    val sandbox = sandboxImage?.let { ContainerSandboxExecutor(it) } ?: DisabledSandboxExecutor()
    val approvedKnowledgeRetriever = ApprovedKnowledgeRetriever(curriculumStore)
    val languageCatalog = LanguageCatalog()
    val councilService = CouncilService(providerRouter, languageCatalog) { prompt, limit -> approvedKnowledgeRetriever.retrieve(prompt, limit) }
    val agentOrchestrator = AgentOrchestrator(
        providers = providerRouter,
        sandbox = sandbox,
        onReviewRequired = { kind, title, summary -> runCatching { reviewQueue.enqueue(kind, title, summary) } },
        retrieveApprovedKnowledge = { task, limit -> approvedKnowledgeRetriever.retrieve(task, limit) }
    )
    val agentTaskManager = agentOrchestrator.manager()
    val sourceService = SourceConnectorService(
        enabled = sourceConnectorsEnabled,
        connectors = listOf(
            WikipediaSourceConnector(ConnectorHttpClient()),
            BraveSearchConnector(ConnectorHttpClient(), safeBraveKey),
            YouTubeMetadataConnector(ConnectorHttpClient(), safeYouTubeKey),
            RedditDiscussionConnector(
                ConnectorHttpClient(userAgent = "VibesQuantumServer/2.6.0 (manual research integration)"),
                safeRedditClientId,
                safeRedditClientSecret
            )
        ),
        store = SourceCandidateStore(sourceStoreFile),
        maxRequestsPerRun = sourceMaxRequestsPerRun,
        maxBytesPerRun = sourceMaxBytesPerRun,
        maxCandidatesPerRun = sourceMaxCandidatesPerRun
    )
    val workspaceEngineering = WorkspaceEngineeringService(
        rootPath = System.getenv("VIBES_WORKSPACE_ROOT")?.takeIf { it.isNotBlank() } ?: "data/workspace-jobs",
        providers = providerRouter,
        sources = sourceService
    )

    val qualityGatePath = System.getenv("VIBES_QUALITY_GATE_PATH")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "data/quality-gate-history.json"
    val qualityGateService = QualityGateService(stateFile = Paths.get(qualityGatePath))
    val memoryLifecycleService = MemoryLifecycleService()
    val unifiedMemoryPath = System.getenv("VIBES_UNIFIED_MEMORY_PATH")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "data/unified-memory-index.enc"
    val unifiedMemoryIndex = UnifiedMemoryIndex(Paths.get(unifiedMemoryPath))

    routing {
        intercept(ApplicationCallPipeline.Call) {
            val correlationId = operations.correlationId(call.request.header("X-Correlation-ID"))
            call.attributes.put(CORRELATION_ID_ATTRIBUTE, correlationId)
            call.response.headers.append("X-Correlation-ID", correlationId, safeOnly = false)
            val startedNanos = System.nanoTime()
            try {
                proceed()
            } finally {
                val elapsedMs = (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000.0
                operations.recordHttp(call.request.local.method.value, call.request.path(), call.response.status()?.value ?: 500, elapsedMs)
            }
        }
        // Language aliases and domain policy metadata are read-only; review states remain explicit.
        qualityLanguageRoutes(languageCatalog)
        // Admin-only operations endpoints expose normalized counts/timings, never prompt or response content.
        get("/api/v1/ops/status") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(OpsStatusResponse(true))
        }
        get("/api/v1/ops/metrics") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(operations.snapshot())
        }
        post("/api/v1/ops/kill-switches") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@post
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val update = try { call.receive<KillSwitchUpdateRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid kill-switch request", "Supply providerRoutingEnabled and/or backgroundLearningEnabled as booleans"))
                return@post
            }
            try {
                call.respond(operations.updateSwitches(update))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Operational switch update failed", "Check server-side state-directory permissions; no switch state was changed."))
            }
        }
        get("/api/v1/ops/limits") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(operations.limits)
        }
        get("/api/v1/ops/learning-session") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(sourceService.liveLearningSession())
        }
        qualityMetadataRoutes()
        securityAndPersonalMemoryRoutes { token ->
            val session = sessions[tokenDigest(token)]
            if (session != null && session.expiresAt > Instant.now()) session.user.id else null
        }
        qualityMemoryRoutes(adminKey, mutationLimiter, qualityGateService, memoryLifecycleService)
        unifiedMemoryRoutes(adminKey, mutationLimiter, unifiedMemoryIndex) { token ->
            sessions[tokenDigest(token)]?.takeIf { it.expiresAt > Instant.now() }?.user?.id
        }
        sourceConnectorRoutes(adminKey, sourceService, mutationLimiter, allowRun = { operations.backgroundLearningEnabled() }, correlationId = { it.attributes[CORRELATION_ID_ATTRIBUTE] })
        workspaceEngineeringRoutes(workspaceEngineering) { call ->
            call.authorizeWorkspaceRequest(adminKey, apiKeys, mutationLimiter)
        }
        curriculumRoutes(adminKey, curriculumService, curriculumStore, mutationLimiter)
        learningRoutes(adminKey, learningStore, LearningKeyAuthorizer { token, appId -> apiKeys.authorize(token, "learning:feedback", appId) }, mutationLimiter)
        councilAndAgentRoutes(
            gatewayAdminKey = adminKey,
            limiter = mutationLimiter,
            providerRouter = providerRouter,
            council = councilService,
            reviewQueue = reviewQueue,
            agentOrchestrator = agentOrchestrator,
            taskManager = agentTaskManager,
            appIdForScope = { token, requiredScope ->
                apiKeys.authenticate(token)?.takeIf { requiredScope in it.scopes }?.appId
            }
        )

        get("/api/v1/auth/google/config") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(GoogleSignInConfigResponse(enabled = safeGoogleClientId != null, clientId = safeGoogleClientId))
        }
        route("/api/v1/auth/google") {
            install(RequestBodyLimit) { bodyLimit { MAX_REQUEST_BODY_BYTES.toLong() } }
            post {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!authLimiter.allow(authLimiter.clientKey(call))) {
                call.response.headers.append("Retry-After", "60", safeOnly = false)
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Sign-in rate limit exceeded", "Try again in about one minute"))
                return@post
            }
            val clientId = safeGoogleClientId
            if (clientId == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Google Sign-In is not configured", "Set GOOGLE_CLIENT_ID on the server and restart it"))
                return@post
            }
            // This endpoint is used by the same-origin console. Reject cross-origin sign-in posts
            // so a third-party site cannot use a victim's browser as the sign-in initiator.
            val requestOrigin = call.request.header("Origin")?.trim()?.trimEnd('/')
            if (requestOrigin.isNullOrBlank() || !requestOrigin.equals(PUBLIC_BASE_URL, ignoreCase = true)) {
                call.respond(HttpStatusCode.Forbidden, ApiError("Google sign-in origin rejected", "Start sign-in from $PUBLIC_BASE_URL."))
                return@post
            }
            val body = try { call.receive<GoogleSignInRequest>() } catch (_: PayloadTooLargeException) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Request body is too large", "Keep the JSON body under $MAX_REQUEST_BODY_BYTES bytes"))
                return@post
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid sign-in request", "Send a JSON body containing idToken or credential"))
                return@post
            }
            val idToken = (body.idToken ?: body.credential)?.trim()
            if (idToken.isNullOrBlank() || idToken.length > 16_384) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Missing Google ID token", "Send the credential returned by Google Identity Services"))
                return@post
            }
            val user = try {
                withContext(Dispatchers.IO) { verifyGoogleIdToken(idToken, clientId) }
            } catch (_: InvalidGoogleToken) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Google credential is invalid or expired", "Sign in again with Google"))
                return@post
            } catch (_: GoogleVerificationUnavailable) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Google sign-in verification is temporarily unavailable", "Check server internet connectivity and try again"))
                return@post
            }
            val now = Instant.now()
            sessions.entries.removeIf { it.value.expiresAt <= now }
            if (sessions.size >= 100_000) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Sign-in capacity temporarily reached", "Try again later"))
                return@post
            }
            val accessToken = newSessionToken()
            val expiresAt = now.plus(Duration.ofHours(8))
            sessions[tokenDigest(accessToken)] = AuthSession(user, expiresAt)
            call.respond(HttpStatusCode.OK, GoogleSignInResponse(accessToken = accessToken, expiresAt = expiresAt.toString(), user = user))
            }
        }
        get("/api/v1/auth/me") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val token = call.bearerToken()
            val session = token?.let { sessions[tokenDigest(it)] }
            if (session == null || session.expiresAt <= Instant.now()) {
                if (token != null) sessions.remove(tokenDigest(token))
                call.respond(HttpStatusCode.Unauthorized, ApiError("Not signed in", "Send a valid Authorization: Bearer token"))
                return@get
            }
            call.respond(CurrentUserResponse(expiresAt = session.expiresAt.toString(), user = session.user))
        }
        post("/api/v1/auth/logout") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val token = call.bearerToken()
            if (token != null) sessions.remove(tokenDigest(token))
            call.respond(LogoutResponse())
        }

        get("/api/v1/keys") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            val keys = apiKeys.list()
            call.respond(ApiKeyListResponse(keys = keys, count = keys.size))
        }
        route("/api/v1/keys") {
            install(RequestBodyLimit) { bodyLimit { MAX_REQUEST_BODY_BYTES.toLong() } }
            post {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@post
            val body = try { call.receive<ApiKeyCreateRequest>() } catch (_: PayloadTooLargeException) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Request body is too large", "Keep the JSON body under $MAX_REQUEST_BODY_BYTES bytes")); return@post
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid key request", "Send name, appId, and an optional scopes list")); return@post
            }
            val keyName = body.name?.trim().orEmpty()
            val appId = body.appId?.trim().orEmpty()
            val scopes = body.scopes.distinct()
            if (!safeText(keyName, 80) || !validId(appId) || scopes.isEmpty() || scopes.any { it !in GatewayApiKeyStore.ALLOWED_APP_KEY_SCOPES }) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid API key request", "Provide a key name, a valid bound appId, and only supported scopes: apps:register, apps:heartbeat, ai:chat, ai:models, learning:feedback, agents:run, workspace:run, workspace:continuous")); return@post
            }
            try {
                val (token, metadata) = apiKeys.create(keyName, appId, scopes)
                call.respond(HttpStatusCode.Created, ApiKeyCreateResponse(apiKey = token, key = metadata))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Could not save API key", "Check that VIBES_API_KEYS_FILE is writable; no key was issued"))
            }
            }
        }
        delete("/api/v1/keys/{keyId}") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@delete
            val id = call.parameters["keyId"]?.trim().orEmpty()
            if (!id.matches(Regex("key_[A-Za-z0-9]{10,40}"))) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid key ID")); return@delete
            }
            try {
                val revoked = apiKeys.revoke(id)
                if (!revoked) call.respond(HttpStatusCode.NotFound, ApiError("Active API key not found"))
                else call.respond(ApiKeyRevocationResponse(revoked = true, id = id))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Could not revoke API key", "Check key-store write permissions"))
            }
        }

        suspend fun respondReadiness(call: ApplicationCall) {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val adminReady = hasStrongGatewayAdminKey(adminKey)
            val inferenceConfigured = safeUpstreamBaseUrl != null && !safeUpstreamApiKey.isNullOrBlank()
            val response = GatewayReadinessResponse(
                ready = adminReady,
                status = if (adminReady) "ready" else "not_ready",
                timestamp = Instant.now().toString(),
                checks = mapOf(
                    "gatewayAdminAuthentication" to adminReady,
                    "registryStoreLoaded" to true,
                    "apiKeyStoreLoaded" to true,
                    "aiInferenceConfigured" to inferenceConfigured
                ),
                securityWarnings = securityWarnings(adminKey)
            )
            call.respond(if (adminReady) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, response)
        }

        get("/api/ready") { respondReadiness(call) }
        get("/readyz") { respondReadiness(call) }

        get("/api/health") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val now = Instant.now()
            call.respond(HealthResponse(
                name = "Vibes Quantum", assistant = "Vibes Vyron X", status = "online", service = "ktor",
                gateway = "ready", timestamp = now.toString(), uptimeSeconds = uptimeSeconds(now),
                registeredApps = registry.snapshot().size, registrationEnabled = hasStrongGatewayAdminKey(adminKey),
                registryMode = if (registry.isPersistent) "file" else "memory",
                securityWarnings = securityWarnings(adminKey)
            ))
        }
        get("/api") {
            call.respond(ApiIndexResponse(
                name = "Vibes Quantum API", version = GATEWAY_VERSION, gateway = "/api/v1/gateway",
                endpoints = listOf(
                    "GET /api/health", "GET /api/ready", "GET /readyz", "GET /api/v1/gateway/info", "GET /api/v1/gateway/stats",
                    "GET /api/v1/auth/google/config", "POST /api/v1/auth/google", "GET /api/v1/auth/me", "POST /api/v1/auth/logout",
                    "GET /api/v1/gateway/ai/status", "GET /api/v1/gateway/usage", "GET /v1/models", "POST /v1/chat/completions",
                    "GET /api/v1/council/roles", "GET /api/v1/council/providers", "POST /v1/council/completions", "GET /api/v1/council/review-queue",
                    "GET /api/v1/agents/capabilities", "POST /v1/agents/tasks", "GET /v1/agents/tasks/{taskId}", "POST /v1/agents/tasks/{taskId}/approve", "DELETE /v1/agents/tasks/{taskId}",
                    "POST /api/v1/workspace/jobs", "PUT /api/v1/workspace/jobs/{jobId}/archive", "GET /api/v1/workspace/jobs/{jobId}", "POST /api/v1/workspace/jobs/{jobId}/stop", "GET /api/v1/workspace/jobs/{jobId}/candidate.zip",
                    "GET /api/v1/apps", "GET /api/v1/apps/{appId}", "POST /api/v1/apps",
                    "PUT /api/v1/apps/{appId}/heartbeat", "DELETE /api/v1/apps/{appId}",
                    "GET /api/v1/keys", "POST /api/v1/keys", "DELETE /api/v1/keys/{keyId}",
                    "GET /api/v1/research/connectors", "GET /api/v1/research/candidates",
                    "GET /api/v1/research/runs", "POST /api/v1/research/runs",
                    "GET /api/v1/curriculum", "GET /api/v1/curriculum/claims", "GET /api/v1/curriculum/examples", "POST /api/v1/curriculum/curricula",
                    "POST /api/v1/curriculum/claims", "PUT /api/v1/curriculum/curricula/{id}", "POST /api/v1/curriculum/generate", "POST /api/v1/curriculum/questions/{id}/review",
                    "GET /api/v1/learning/apps/{appId}/policy", "PUT /api/v1/learning/apps/{appId}/policy", "GET /api/v1/learning/apps/{appId}/summary", "GET /api/v1/learning/apps/{appId}/feedback", "POST /api/v1/learning/consent", "POST /api/v1/learning/feedback", "POST /api/v1/learning/subjects/export", "POST /api/v1/learning/subjects/delete"
                )
            ))
        }
        get("/api/v1/gateway/info") {
            call.respond(GatewayInfoResponse(
                name = "Vibes Quantum Gateway", version = GATEWAY_VERSION,
                purpose = "Shared Vibes app directory and scoped OpenAI-compatible AI inference gateway",
                registrationEnabled = hasStrongGatewayAdminKey(adminKey),
                authentication = "X-Vibes-Gateway-Key for gateway administration; X-Vibes-API-Key for app-bound registration/heartbeat; Google ID-token login with short-lived bearer sessions when GOOGLE_CLIENT_ID is configured",
                supportedTransports = listOf("HTTPS", "localhost HTTP for development"),
                registry = if (registry.isPersistent) "file-backed JSON; path configured by VIBES_REGISTRY_FILE" else "in-memory; entries reset when the server restarts",
                offlineAfterSeconds = OFFLINE_AFTER_SECONDS,
                mutationLimitPerMinute = MUTATION_LIMIT_PER_MINUTE,
                features = listOf(
                    "OpenAI-compatible chat completions proxy", "allow-listed models", "server-side provider credentials",
                    "validated text requests", "OpenAI-compatible SSE streaming", "per-app chat rate limiting", "request IDs", "privacy-preserving usage counters",
                    "optional multi-provider AI council", "configurable local-first provider router", "six scoped task agents", "approval-gated actions", "fail-closed container sandbox",
                    "live health", "registry search", "capability filtering", "pagination", "heartbeat tracking",
                    "gateway statistics", "atomic file persistence", "authenticated mutations", "mutation rate limiting",
                    "input validation", "safe error responses", "Google ID-token signature and claim verification",
                    "short-lived user sessions", "bearer-token authentication", "session logout", "sign-in rate limiting",
                    "per-app API keys", "hashed API key storage", "app-bound key scopes", "key revocation", "one-time key display",
                    "manual opt-in source learning", "pending evidence review", "bounded source connector budgets",
                    "tenant-scoped feedback policy", "explicit consent and opt-out", "pseudonymous subject references",
                    "tenant-scoped export and deletion", "retention and daily quotas", "pending-review teaching examples",
                    "correlation IDs", "normalized request latency and error counters", "measured JVM and file-storage metrics",
                    "configurable provider timeout/concurrency/retry budgets", "provider circuit breaker", "persisted provider/learning kill switches"
                )
            ))
        }
        get("/api/v1/gateway/stats") {
            val now = Instant.now()
            val snapshot = registry.snapshot().map { effectiveRecord(it, now) }
            val capabilityCounts = snapshot.flatMap { app -> app.capabilities.map { it.lowercase() }.distinct() }
                .groupingBy { it }.eachCount().toSortedMap()
            call.respond(GatewayStatsResponse(
                generatedAt = now.toString(), uptimeSeconds = uptimeSeconds(now),
                registrationEnabled = hasStrongGatewayAdminKey(adminKey), totalApps = snapshot.size,
                onlineApps = snapshot.count { it.status == "online" },
                offlineApps = snapshot.count { it.status == "offline" }, capabilities = capabilityCounts
            ))
        }
        get("/api/v1/gateway/ai/status") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(GatewayAiStatusResponse(
                enabled = safeUpstreamBaseUrl != null && !safeUpstreamApiKey.isNullOrBlank() && modelAllowlist.isNotEmpty(),
                defaultModel = safeUpstreamModel,
                models = modelAllowlist.toList(),
                streamingSupported = true
            ))
        }
        get("/api/v1/gateway/usage") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@get
            call.respond(usageRecorder.snapshot())
        }
        get("/v1/models") {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val token = call.request.header("X-Vibes-API-Key") ?: call.bearerToken()
            val principal = apiKeys.authenticate(token)
            if (principal == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Valid Vibes app API key required", "Send the key in X-Vibes-API-Key or Authorization: Bearer")); return@get
            }
            if ("ai:models" !in principal.scopes && "ai:chat" !in principal.scopes) {
                call.respond(HttpStatusCode.Forbidden, ApiError("API key does not have model-list permission", "Issue a key with ai:models or ai:chat scope")); return@get
            }
            call.respond(GatewayModelList(data = modelAllowlist.map { GatewayModel(id = it) }))
        }
        route("/v1/chat/completions") {
          install(RequestBodyLimit) {
              bodyLimit { MAX_REQUEST_BODY_BYTES.toLong() }
          }
          post {
            val requestId = "vqreq_" + UUID.randomUUID().toString().replace("-", "").take(18)
            call.response.headers.append("X-Request-ID", requestId, safeOnly = false)
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val token = call.request.header("X-Vibes-API-Key") ?: call.bearerToken()
            val principal = apiKeys.authenticate(token)
            if (principal == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Valid Vibes app API key required", "Send the app-bound key in X-Vibes-API-Key or Authorization: Bearer")); return@post
            }
            if ("ai:chat" !in principal.scopes) {
                call.respond(HttpStatusCode.Forbidden, ApiError("API key does not have AI chat permission", "Create a key for this app with the ai:chat scope")); return@post
            }
            if (!chatLimiter.allow(principal.id) || !appChatLimiter.allow("app:${principal.appId}")) {
                call.response.headers.append("Retry-After", "60", safeOnly = false)
                call.respond(HttpStatusCode.TooManyRequests, ApiError("AI request rate limit exceeded", "Both API-key and app-level chat quotas apply. Retry after the current minute window. Request ID: $requestId")); return@post
            }
            if (!operations.providerRoutingEnabled()) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Provider routing is disabled by the operator", "The provider-routing kill switch is active. Retry only after an administrator re-enables provider routing. Request ID: $requestId")); return@post
            }
            if (!operations.mayRouteProvider()) {
                call.response.headers.append("Retry-After", "30", safeOnly = false)
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Provider circuit breaker is open", "The provider is temporarily paused after repeated failures. Retry after the circuit reset window. Request ID: $requestId")); return@post
            }
            if (safeUpstreamBaseUrl.isNullOrBlank() || safeUpstreamApiKey.isNullOrBlank() || modelAllowlist.isEmpty()) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("AI inference is not configured", "Set VIBES_UPSTREAM_BASE_URL, VIBES_UPSTREAM_API_KEY, and at least one validated model ID in VIBES_UPSTREAM_MODEL or VIBES_ALLOWED_MODELS. Request ID: $requestId")); return@post
            }
            val declaredLength = call.request.headers["Content-Length"]?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_REQUEST_BODY_BYTES) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Request body is too large", "Keep the JSON body under $MAX_REQUEST_BODY_BYTES bytes. Request ID: $requestId")); return@post
            }
            val rawBody = try { call.receiveText() } catch (_: PayloadTooLargeException) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Request body is too large", "Keep the JSON body under $MAX_REQUEST_BODY_BYTES bytes. Request ID: $requestId")); return@post
            } catch (_: Exception) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadRequest, ApiError("Could not read JSON request body", "Request ID: $requestId")); return@post
            }
            val normalized = try { normalizeChatCompletionRequest(rawBody, safeUpstreamModel, modelAllowlist, operations.limits.requestTokenBudget) } catch (failure: InvalidChatRequest) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadRequest, ApiError(failure.message ?: "Invalid chat completion request", "Request ID: $requestId")); return@post
            }
            val request = try {
                HttpRequest.newBuilder(URI(safeUpstreamBaseUrl.trimEnd('/') + "/chat/completions"))
                    .timeout(Duration.ofMillis(minOf(operations.limits.providerTimeoutMs, operations.limits.requestElapsedBudgetMs)))
                    .header("Authorization", "Bearer $safeUpstreamApiKey")
                    .header("X-Correlation-ID", call.attributes[CORRELATION_ID_ATTRIBUTE])
                    .header("Content-Type", "application/json")
                    .header("Accept", if (normalized.streaming) "text/event-stream" else "application/json")
                    .header("User-Agent", "VibesQuantumGateway/$GATEWAY_VERSION")
                    .POST(HttpRequest.BodyPublishers.ofString(normalized.body, StandardCharsets.UTF_8))
                    .build()
            } catch (_: Exception) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("AI upstream configuration is invalid", "Check VIBES_UPSTREAM_BASE_URL on the server. Request ID: $requestId")); return@post
            }
            if (!operations.tryAcquireProviderSlot()) {
                call.response.headers.append("Retry-After", "2", safeOnly = false)
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Provider concurrency limit reached", "Wait briefly and retry. The request was not sent to the provider. Request ID: $requestId"))
                return@post
            }
            try {
            val sent = try {
                withContext(Dispatchers.IO) { safeProviderSend(request, operations) }
            } catch (failure: ProviderSendFailure) {
                operations.recordProvider(failure.latencyMs, 0, false, failure.retries)
                if (failure.cause is InterruptedException) Thread.currentThread().interrupt()
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadGateway, ApiError("Could not reach the configured AI provider", "Check server connectivity, endpoint configuration, and provider availability. Request ID: $requestId"))
                return@post
            } catch (_: Exception) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadGateway, ApiError("Could not reach the configured AI provider", "Check server connectivity, endpoint configuration, and provider availability. Request ID: $requestId"))
                return@post
            }
            val upstreamResponse = sent.response
            operations.recordProvider(sent.latencyMs, upstreamResponse.statusCode(), upstreamResponse.statusCode() in 200..299, sent.retries)
            if (upstreamResponse.statusCode() !in 200..299) {
                val upstreamBytes = withContext(Dispatchers.IO) {
                    upstreamResponse.body().use { it.readNBytes(MAX_UPSTREAM_RESPONSE_BYTES + 1) }
                }
                usageRecorder.record(principal.appId, success = false)
                val safeMessage = when (upstreamResponse.statusCode()) {
                    401, 403 -> "AI upstream credentials or permissions were rejected"
                    429 -> "AI upstream rate or quota limit reached"
                    in 500..599 -> "AI upstream is temporarily unavailable"
                    else -> "AI upstream rejected the request (HTTP ${upstreamResponse.statusCode()})"
                }
                // Never relay provider error bodies or secrets to the app client.
                call.respond(HttpStatusCode.BadGateway, ApiError(safeMessage, "Check server-side provider configuration and quotas. Request ID: $requestId"))
                return@post
            }
            call.response.headers.append("X-Vibes-App-ID", principal.appId, safeOnly = false)
            call.response.headers.append("X-Vibes-Model", normalized.model, safeOnly = false)
            if (normalized.streaming && !upstreamResponse.headers().firstValue("Content-Type").orElse("").lowercase().contains("text/event-stream")) {
                // Do not disguise a non-streaming JSON response as SSE; the client must know real provider streaming is unavailable.
                withContext(Dispatchers.IO) { upstreamResponse.body().use { it.readNBytes(MAX_UPSTREAM_RESPONSE_BYTES + 1) } }
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadGateway, ApiError("Configured AI provider did not return a server-sent event stream", "Choose a streaming-capable provider/endpoint. Request ID: $requestId"))
                return@post
            }
            if (normalized.streaming) {
                call.response.headers.append("Cache-Control", "no-cache, no-transform", safeOnly = false)
                call.response.headers.append("X-Accel-Buffering", "no", safeOnly = false)
                try {
                    call.respondOutputStream(contentType = ContentType.parse("text/event-stream; charset=utf-8"), status = HttpStatusCode.OK) {
                        upstreamResponse.body().use { input ->
                            val buffer = ByteArray(8_192)
                            var totalBytes = 0L
                            var overflowed = false
                            while (!overflowed) {
                                val count = withContext(Dispatchers.IO) { input.read(buffer) }
                                if (count < 0) break
                                if (totalBytes + count.toLong() > MAX_UPSTREAM_RESPONSE_BYTES.toLong()) {
                                    val allowed = (MAX_UPSTREAM_RESPONSE_BYTES.toLong() - totalBytes).coerceAtLeast(0L).toInt()
                                    if (allowed > 0) { write(buffer, 0, allowed); flush() }
                                    write("\ndata: {\"error\":{\"message\":\"Streaming response exceeded the gateway response limit\"}}\n\n".toByteArray(StandardCharsets.UTF_8))
                                    write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
                                    flush()
                                    overflowed = true
                                } else {
                                    write(buffer, 0, count)
                                    flush()
                                    totalBytes += count
                                }
                            }
                        }
                    }
                    // Stream usage tokens are provider-dependent and may not be present in the final chunk.
                    usageRecorder.record(principal.appId, success = true)
                } catch (_: Exception) {
                    // A client disconnect should stop the upstream copy and must not crash the server.
                    runCatching { upstreamResponse.body().close() }
                    usageRecorder.record(principal.appId, success = false)
                }
                return@post
            }
            val upstreamBytes = withContext(Dispatchers.IO) {
                upstreamResponse.body().use { it.readNBytes(MAX_UPSTREAM_RESPONSE_BYTES + 1) }
            }
            if (upstreamBytes.size > MAX_UPSTREAM_RESPONSE_BYTES) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadGateway, ApiError("AI provider response exceeded the gateway response limit", "Request ID: $requestId"))
                return@post
            }
            val upstreamBody = upstreamBytes.toString(StandardCharsets.UTF_8)
            val jsonResponse = runCatching { Json.parseToJsonElement(upstreamBody) as? JsonObject }.getOrNull()
            if (jsonResponse == null || jsonResponse["choices"] !is JsonArray) {
                usageRecorder.record(principal.appId, success = false)
                call.respond(HttpStatusCode.BadGateway, ApiError("AI upstream returned an invalid chat-completion response", "Request ID: $requestId"))
                return@post
            }
            // Recorded once per request. A client disconnect while sending no longer counts the request twice.
            val tokenUsage = extractTokenUsage(upstreamBody)
            val (promptTokens, completionTokens) = tokenUsage ?: (0L to 0L)
            if (tokenUsage != null) operations.recordProviderUsage(promptTokens, completionTokens)
            usageRecorder.record(principal.appId, success = true, promptTokens = promptTokens, completionTokens = completionTokens)
            call.response.headers.append("X-Vibes-App-ID", principal.appId, safeOnly = false)
            call.response.headers.append("X-Vibes-Model", normalized.model, safeOnly = false)
            call.respondText(upstreamBody, ContentType.Application.Json, HttpStatusCode.OK)
            } finally {
                // The semaphore bounds active body streaming and buffered responses, not just request startup.
                operations.releaseProviderSlot()
            }
          }
        }
        get("/api/v1/apps") {
            val params = call.request.queryParameters
            val q = params["q"]?.trim()?.takeIf { it.isNotEmpty() }?.take(100)
            val capability = params["capability"]?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
            val status = params["status"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (status != null && status !in setOf("online", "offline")) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid status filter", "Allowed values are online and offline")); return@get
            }
            val limitRaw = params["limit"]
            val offsetRaw = params["offset"]
            val parsedLimit = limitRaw?.toIntOrNull()
            val parsedOffset = offsetRaw?.toIntOrNull()
            if ((limitRaw != null && (parsedLimit == null || parsedLimit !in 1..MAX_PAGE_SIZE)) ||
                (offsetRaw != null && (parsedOffset == null || parsedOffset !in 0..1_000_000))) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid pagination", "limit must be 1..$MAX_PAGE_SIZE and offset must be 0..1000000")); return@get
            }
            val limit = parsedLimit ?: 50
            val offset = parsedOffset ?: 0
            val needle = q?.lowercase()
            val filtered = registry.snapshot().asSequence().map { effectiveRecord(it) }
                .filter { app -> needle == null || listOf(app.appId, app.displayName, app.baseUrl, app.version).any { it.lowercase().contains(needle) } || app.capabilities.any { it.lowercase().contains(needle) } }
                .filter { app -> capability == null || app.capabilities.any { it.equals(capability, ignoreCase = true) } }
                .filter { app -> status == null || app.status == status }
                .sortedBy { it.appId.lowercase() }
                .toList()
            val page = filtered.drop(offset).take(limit)
            call.respond(AppListResponse(
                count = page.size, total = filtered.size, limit = limit, offset = offset, apps = page,
                filters = AppSearchFilters(q = q, capability = capability, status = status)
            ))
        }
        get("/api/v1/apps/{appId}") {
            val id = call.parameters["appId"]
            if (id.isNullOrBlank() || !validId(id)) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid appId")); return@get }
            val app = registry[id]
            if (app == null) call.respond(HttpStatusCode.NotFound, ApiError("App not found")) else call.respond(effectiveRecord(app))
        }
        route("/api/v1/apps") {
            install(RequestBodyLimit) { bodyLimit { MAX_REQUEST_BODY_BYTES.toLong() } }
            post {
            val input = try { call.receive<VibesAppRegistration>() } catch (_: PayloadTooLargeException) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Request body is too large", "Keep the JSON body under $MAX_REQUEST_BODY_BYTES bytes")); return@post
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON registration payload")); return@post
            }
            if (!call.authorizeAppMutation(adminKey, apiKeys, mutationLimiter, "apps:register", input.appId)) return@post
            val cleanCapabilities = input.capabilities.map { it.trim() }
            if (!validId(input.appId) || !safeText(input.displayName.trim(), 100) || !validBaseUrl(input.baseUrl) ||
                !safeText(input.version.trim(), 40) || input.capabilities.size > 40 ||
                cleanCapabilities.any { !safeText(it, 80) } || cleanCapabilities.map { it.lowercase() }.distinct().size != cleanCapabilities.size) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid registration", "Use a valid appId, displayName, version, up to 40 unique capabilities, and an HTTPS baseUrl (localhost HTTP is allowed for development)")); return@post
            }
            try {
                val (savedRecord, wasCreated) = registry.upsert(input, cleanCapabilities, Instant.now().toString())
                call.respond(if (wasCreated) HttpStatusCode.Created else HttpStatusCode.OK, savedRecord)
            } catch (_: RegistryPersistenceException) {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Could not save registry", "Check that the configured registry directory is writable; the update was rolled back"))
            }
            }
        }
        put("/api/v1/apps/{appId}/heartbeat") {
            val id = call.parameters["appId"]
            if (id.isNullOrBlank() || !validId(id)) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid appId")); return@put }
            if (!call.authorizeAppMutation(adminKey, apiKeys, mutationLimiter, "apps:heartbeat", id)) return@put
            try {
                val record = registry.heartbeat(id, Instant.now().toString())
                if (record == null) call.respond(HttpStatusCode.NotFound, ApiError("App not found")) else call.respond(record)
            } catch (_: RegistryPersistenceException) {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Could not save heartbeat", "Check registry storage permissions; the update was rolled back"))
            }
        }
        delete("/api/v1/apps/{appId}") {
            if (!call.authorizeMutation(adminKey, mutationLimiter)) return@delete
            val id = call.parameters["appId"]
            if (id.isNullOrBlank() || !validId(id)) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid appId")); return@delete }
            try {
                if (!registry.remove(id)) call.respond(HttpStatusCode.NotFound, ApiError("App not found"))
                else call.respond(HttpStatusCode.OK, AppRemovalResponse(removed = true, appId = id))
            } catch (_: RegistryPersistenceException) {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Could not save registry", "Check registry storage permissions; the deletion was rolled back"))
            }
        }
        staticResources("/console", "static-console", index = "index.html")
        staticResources("/", "static", index = "index.html")
    }
}
