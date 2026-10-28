package com.vibes.quantum.server.learning

import com.vibes.quantum.server.MutationRateLimiter
import com.vibes.quantum.server.authorizeMutation
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import io.ktor.server.application.call
import io.ktor.server.application.install

/**
 * Phase 8 tenant learning endpoints. App endpoints require a scoped, app-bound API key.
 * Admin endpoints use the same strong gateway-key guard as curriculum/research administration.
 */
fun Route.learningRoutes(
    gatewayKey: String?,
    service: LearningStore,
    apiKeys: LearningKeyAuthorizer,
    limiter: MutationRateLimiter
) {
    route("/api/v1/learning") {
        get("/apps/{appId}/policy") {
            if (!call.authorizeMutation(gatewayKey, limiter)) return@get
            val appId = call.parameters["appId"]?.takeIf(::validAppId)
            if (appId == null) { call.respond(HttpStatusCode.BadRequest, LearningError("Invalid app ID")); return@get }
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(service.policy(appId))
        }
        put("/apps/{appId}/policy") {
            if (!call.authorizeMutation(gatewayKey, limiter)) return@put
            val appId = call.parameters["appId"]?.takeIf(::validAppId)
            if (appId == null) { call.respond(HttpStatusCode.BadRequest, LearningError("Invalid app ID")); return@put }
            val body = call.receiveLearningBody<LearningPolicyUpdate>() ?: return@put
            try { call.respond(service.updatePolicy(appId, body)) }
            catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, LearningError(failure.message ?: "Invalid learning policy")) }
            catch (_: Exception) { call.respond(HttpStatusCode.InternalServerError, LearningError("Could not save learning policy", "Check VIBES_LEARNING_STORE_FILE write permissions")) }
        }
        get("/apps/{appId}/summary") {
            if (!call.authorizeMutation(gatewayKey, limiter)) return@get
            val appId = call.parameters["appId"]?.takeIf(::validAppId)
            if (appId == null) { call.respond(HttpStatusCode.BadRequest, LearningError("Invalid app ID")); return@get }
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(service.summary(appId))
        }
        get("/apps/{appId}/feedback") {
            if (!call.authorizeMutation(gatewayKey, limiter)) return@get
            val appId = call.parameters["appId"]?.takeIf(::validAppId)
            if (appId == null) { call.respond(HttpStatusCode.BadRequest, LearningError("Invalid app ID")); return@get }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            if (limit !in 1..100 || offset !in 0..100_000) { call.respond(HttpStatusCode.BadRequest, LearningError("Invalid pagination", "limit must be 1–100 and offset 0–100000")); return@get }
            val (total, rows) = service.page(appId, limit, offset)
            // Tenant-subject hashes are internal join keys, not dashboard data.
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(LearningFeedbackPage(appId, rows.size, total, limit, offset, rows.map { it.copy(subjectHash = "[redacted]") }))
        }
        install(RequestBodyLimit) { bodyLimit { 65_536L } }
        post("/consent") {
            val body = call.receiveLearningBody<LearningConsentRequest>() ?: return@post
            if (!validAppId(body.appId)) { call.respond(HttpStatusCode.BadRequest, LearningError("Invalid app ID")); return@post }
            if (!call.authorizeLearningApp(apiKeys, limiter, body.appId)) return@post
            try {
                val (record, deleted) = service.setConsent(body.appId, body.subjectRef, body.granted, body.categories, body.noticeVersion)
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                call.respond(LearningConsentStatus(record.appId, record.granted, record.categories, record.noticeVersion, record.updatedAt, deleted))
            } catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, LearningError(failure.message ?: "Invalid consent request")) }
            catch (_: Exception) { call.respond(HttpStatusCode.InternalServerError, LearningError("Could not save consent", "Check learning-store write permissions")) }
        }
        post("/feedback") {
            val body = call.receiveLearningBody<LearningFeedbackRequest>() ?: return@post
            if (!validAppId(body.appId)) { call.respond(HttpStatusCode.BadRequest, LearningError("Invalid app ID")); return@post }
            if (!call.authorizeLearningApp(apiKeys, limiter, body.appId)) return@post
            try {
                val result = service.addFeedback(body)
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                call.respond(if (result.duplicate) HttpStatusCode.OK else HttpStatusCode.Created, result)
            } catch (failure: LearningConsentRequired) { call.respond(HttpStatusCode.Forbidden, LearningError(failure.message ?: "Consent required")) }
            catch (failure: LearningQuotaExceeded) { call.response.headers.append("Retry-After", "3600", safeOnly = false); call.respond(HttpStatusCode.TooManyRequests, LearningError(failure.message ?: "Daily quota reached")) }
            catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, LearningError(failure.message ?: "Invalid feedback")) }
            catch (_: Exception) { call.respond(HttpStatusCode.InternalServerError, LearningError("Could not save feedback", "Check learning-store write permissions")) }
        }
        post("/subjects/export") {
            val body = call.receiveLearningBody<LearningSubjectRequest>() ?: return@post
            if (!validAppId(body.appId)) { call.respond(HttpStatusCode.BadRequest, LearningError("Invalid app ID")); return@post }
            if (!call.authorizeLearningApp(apiKeys, limiter, body.appId)) return@post
            try { call.response.headers.append("Cache-Control", "no-store", safeOnly = false); call.respond(service.exportSubject(body.appId, body.subjectRef)) }
            catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, LearningError(failure.message ?: "Invalid export request")) }
        }
        post("/subjects/delete") {
            val body = call.receiveLearningBody<LearningSubjectRequest>() ?: return@post
            if (!validAppId(body.appId)) { call.respond(HttpStatusCode.BadRequest, LearningError("Invalid app ID")); return@post }
            if (!call.authorizeLearningApp(apiKeys, limiter, body.appId)) return@post
            try {
                val deleted = service.deleteSubject(body.appId, body.subjectRef)
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                call.respond(LearningDeleteResponse(body.appId, true, deleted, "Subject consent and individual feedback records were removed for this app."))
            } catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, LearningError(failure.message ?: "Invalid delete request")) }
            catch (_: Exception) { call.respond(HttpStatusCode.InternalServerError, LearningError("Could not delete subject data", "Check learning-store write permissions")) }
        }
    }
}

@Serializable
data class LearningSubjectRequest(val appId: String, val subjectRef: String)

@Serializable
data class LearningDeleteResponse(val appId: String, val deleted: Boolean, val deletedSignals: Int, val note: String)

/** Narrow adapter around the gateway's key store, implemented in the gateway module. */
fun interface LearningKeyAuthorizer {
    fun authorize(token: String?, appId: String): Boolean
}

private suspend fun ApplicationCall.authorizeLearningApp(apiKeys: LearningKeyAuthorizer, limiter: MutationRateLimiter, appId: String): Boolean {
    if (!limiter.allow(limiter.clientKey(this))) {
        response.headers.append("Retry-After", "60", safeOnly = false)
        respond(HttpStatusCode.TooManyRequests, LearningError("Learning API rate limit exceeded", "Retry after about one minute"))
        return false
    }
    val token = request.header("X-Vibes-API-Key") ?: request.header("Authorization")?.takeIf { it.startsWith("Bearer ", true) }?.substringAfter(' ')
    if (apiKeys.authorize(token, appId)) return true
    respond(HttpStatusCode.Unauthorized, LearningError("App-scoped learning permission required", "Issue a key bound to this exact app ID with learning:feedback scope. The app must only submit after consent."))
    return false
}

private fun validAppId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))

private suspend inline fun <reified T : Any> ApplicationCall.receiveLearningBody(): T? = try {
    receive<T>()
} catch (_: PayloadTooLargeException) {
    respond(HttpStatusCode.PayloadTooLarge, LearningError("Request is too large", "Keep the JSON body below 65,536 bytes.")); null
} catch (_: Exception) {
    respond(HttpStatusCode.BadRequest, LearningError("Invalid JSON request", "Check the required fields and data types.")); null
}
