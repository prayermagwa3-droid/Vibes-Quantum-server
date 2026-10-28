package com.vibes.quantum.server.ai

import com.vibes.quantum.server.MutationRateLimiter
import com.vibes.quantum.server.authorizeMutation
import com.vibes.quantum.server.agents.AgentApiError
import com.vibes.quantum.server.agents.AgentOrchestrator
import com.vibes.quantum.server.agents.AgentTaskRequest
import com.vibes.quantum.server.agents.AgentTaskManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import io.ktor.server.application.call
import io.ktor.server.application.install

/** The authorizer returns an app ID only when the supplied app-bound key has the required scope. */
fun Route.councilAndAgentRoutes(
    gatewayAdminKey: String?,
    limiter: MutationRateLimiter,
    providerRouter: ProviderRouter,
    council: CouncilService,
    reviewQueue: AdminReviewQueue,
    agentOrchestrator: AgentOrchestrator,
    taskManager: AgentTaskManager,
    appIdForScope: (token: String?, requiredScope: String) -> String?
) {
    get("/api/v1/council/roles") {
        call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
        call.respond(council.roleDirectory())
    }
    get("/api/v1/agents/capabilities") {
        call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
        call.respond(agentOrchestrator.directory())
    }
    get("/api/v1/council/providers") {
        if (!call.authorizeMutation(gatewayAdminKey, limiter)) return@get
        call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
        call.respond(providerRouter.publicInfo())
    }

    route("/v1/council/completions") {
        install(RequestBodyLimit) { bodyLimit { 65_536L } }
        post {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val appId = call.authorizeScopedAppKey(limiter, appIdForScope, "ai:chat") ?: return@post
            val input = call.receiveLimited<CouncilRequest>() ?: return@post
            val response = try { council.answer(input) }
            catch (failure: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, AgentApiError(failure.message ?: "Invalid council request")); return@post
            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                call.respond(HttpStatusCode.GatewayTimeout, AgentApiError("Council deadline exceeded", "Try Standard mode, reduce the number of member roles, or increase the configured request deadline.")); return@post
            } catch (_: Exception) {
                call.respond(HttpStatusCode.ServiceUnavailable, AgentApiError("Council could not complete the request", "Check configured provider health, budgets and server logs; provider errors are not exposed to clients.")); return@post
            }
            if (response.disagreements.isNotEmpty()) {
                reviewQueue.enqueue("conflict", "AI council disagreement", "A council request from app '$appId' produced ${response.disagreements.size} explicit conflict note(s). Inspect the supplied evidence before approving claims.")
            }
            if (response.normalizedClaims.isNotEmpty() && response.unsupportedOrUnverified.isNotEmpty()) {
                reviewQueue.enqueue("claim_review", "Candidate answer needs claim review", "A council request from app '$appId' contained normalized candidate claims with evidence gaps or duplicated sources; source text and user prompt were not stored in this queue entry.")
            }
            call.respond(HttpStatusCode.OK, response)
        }
    }

    route("/v1/agents/tasks") {
        install(RequestBodyLimit) { bodyLimit { 65_536L } }
        post {
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val appId = call.authorizeScopedAppKey(limiter, appIdForScope, "agents:run") ?: return@post
            val input = call.receiveLimited<AgentTaskRequest>() ?: return@post
            val record = try { taskManager.submit(appId, input, call.request.header("Idempotency-Key")) }
            catch (failure: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, AgentApiError(failure.message ?: "Invalid task request")); return@post
            } catch (_: IllegalStateException) {
                call.respond(HttpStatusCode.ServiceUnavailable, AgentApiError("Agent task capacity or state is temporarily unavailable", "Retry with the same Idempotency-Key or inspect completed tasks.")); return@post
            }
            val status = if (record.status == "pending_approval") HttpStatusCode.Accepted else HttpStatusCode.Accepted
            call.respond(status, record)
        }
        get {
            val appId = call.authorizeScopedAppKey(limiter, appIdForScope, "agents:run") ?: return@get
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            if (limit !in 1..100) { call.respond(HttpStatusCode.BadRequest, AgentApiError("limit must be between 1 and 100")); return@get }
            call.respond(taskManager.list(appId, limit))
        }
        get("/{taskId}") {
            val appId = call.authorizeScopedAppKey(limiter, appIdForScope, "agents:run") ?: return@get
            val id = call.parameters["taskId"]?.takeIf { it.matches(Regex("task_[A-Za-z0-9]{10,40}")) }
            if (id == null) { call.respond(HttpStatusCode.BadRequest, AgentApiError("Invalid task id")); return@get }
            val record = taskManager.get(appId, id)
            if (record == null) call.respond(HttpStatusCode.NotFound, AgentApiError("Task not found")) else call.respond(record)
        }
        post("/{taskId}/approve") {
            val appId = call.authorizeScopedAppKey(limiter, appIdForScope, "agents:run") ?: return@post
            val id = call.parameters["taskId"]?.takeIf { it.matches(Regex("task_[A-Za-z0-9]{10,40}")) }
            if (id == null) { call.respond(HttpStatusCode.BadRequest, AgentApiError("Invalid task id")); return@post }
            val record = taskManager.approve(appId, id)
            if (record == null) call.respond(HttpStatusCode.NotFound, AgentApiError("Task not found or approval was rejected"))
            else call.respond(HttpStatusCode.OK, record)
        }
        delete("/{taskId}") {
            val appId = call.authorizeScopedAppKey(limiter, appIdForScope, "agents:run") ?: return@delete
            val id = call.parameters["taskId"]?.takeIf { it.matches(Regex("task_[A-Za-z0-9]{10,40}")) }
            if (id == null) { call.respond(HttpStatusCode.BadRequest, AgentApiError("Invalid task id")); return@delete }
            val record = taskManager.cancel(appId, id)
            if (record == null) call.respond(HttpStatusCode.NotFound, AgentApiError("Task not found")) else call.respond(HttpStatusCode.OK, record)
        }
    }

    route("/api/v1/council/review-queue") {
        get {
            if (!call.authorizeMutation(gatewayAdminKey, limiter)) return@get
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            if (limit !in 1..200) { call.respond(HttpStatusCode.BadRequest, AgentApiError("limit must be between 1 and 200")); return@get }
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(reviewQueue.snapshot(limit))
        }
        post("/{reviewId}/review") {
            if (!call.authorizeMutation(gatewayAdminKey, limiter)) return@post
            val id = call.parameters["reviewId"]?.takeIf { it.matches(Regex("review_[A-Za-z0-9]{10,40}")) }
            if (id == null) { call.respond(HttpStatusCode.BadRequest, AgentApiError("Invalid review id")); return@post }
            val request = call.receiveLimited<AdminReviewRequest>() ?: return@post
            val updated = try { reviewQueue.review(id, request) }
            catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, AgentApiError(failure.message ?: "Invalid review decision")); return@post }
            if (updated == null) call.respond(HttpStatusCode.NotFound, AgentApiError("Review item not found")) else call.respond(updated)
        }
    }
}

private suspend fun ApplicationCall.authorizeScopedAppKey(
    limiter: MutationRateLimiter,
    appIdForScope: (String?, String) -> String?,
    scope: String
): String? {
    if (!limiter.allow(limiter.clientKey(this))) {
        response.headers.append("Retry-After", "60", safeOnly = false)
        respond(HttpStatusCode.TooManyRequests, AgentApiError("Request rate limit exceeded", "Retry in about one minute."))
        return null
    }
    val header = request.header("X-Vibes-API-Key")
    val bearer = request.header("Authorization")?.trim()?.takeIf { it.startsWith("Bearer ", true) }?.substringAfter(' ')?.trim()
    val appId = appIdForScope(header ?: bearer, scope)
    if (appId == null) {
        respond(HttpStatusCode.Unauthorized, AgentApiError("A valid app-bound key with the required scope is required", "Use an API key with '$scope'; keys and task records are app-scoped."))
        return null
    }
    return appId
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveLimited(): T? = try {
    receive<T>()
} catch (_: PayloadTooLargeException) {
    respond(HttpStatusCode.PayloadTooLarge, AgentApiError("Request body is too large", "Keep the JSON request below 65,536 bytes.")); null
} catch (_: Exception) {
    respond(HttpStatusCode.BadRequest, AgentApiError("Invalid JSON request", "Check required fields and data types.")); null
}
