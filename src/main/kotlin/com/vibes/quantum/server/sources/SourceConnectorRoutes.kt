package com.vibes.quantum.server.sources

import com.vibes.quantum.server.ApiError
import com.vibes.quantum.server.MutationRateLimiter
import com.vibes.quantum.server.authorizeMutation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.application.install

fun Route.sourceConnectorRoutes(
    gatewayKey: String?,
    service: SourceConnectorService,
    mutationLimiter: MutationRateLimiter,
    allowRun: () -> Boolean = { true },
    correlationId: (ApplicationCall) -> String? = { null }
) {
    route("/api/v1/research") {
        route("/connectors") {
            get {
                if (!call.authorizeMutation(gatewayKey, mutationLimiter)) return@get
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                call.respond(service.snapshot())
            }
        }
        get("/candidates") {
            if (!call.authorizeMutation(gatewayKey, mutationLimiter)) return@get
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 25
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            if (limit !in 1..100 || offset !in 0..1_000_000) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid pagination", "limit must be 1–100 and offset 0–1000000"))
                return@get
            }
            call.respond(service.pendingCandidates(limit, offset))
        }
        get("/runs") {
            if (!call.authorizeMutation(gatewayKey, mutationLimiter)) return@get
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(service.snapshot().recentRuns)
        }
        route("/runs") {
            install(RequestBodyLimit) { bodyLimit { 65_536L } }
            post {
                if (!call.authorizeMutation(gatewayKey, mutationLimiter)) return@post
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                if (!allowRun()) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Background/source learning is paused by the operator", "Re-enable backgroundLearningEnabled in the admin operational controls before starting a new source-learning run."))
                    return@post
                }
                val request = try { call.receive<SourceRunRequest>() }
                catch (_: PayloadTooLargeException) {
                    call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Research request is too large", "Keep the JSON request below 65,536 bytes."))
                    return@post
                }
                catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Invalid research request", "Send JSON with query, reasonForResearch, and optional connectorIds."))
                    return@post
                }
                try {
                    // The connector implementation uses bounded blocking Java HTTP calls; keep them off Netty's call thread.
                    val response = withContext(Dispatchers.IO) { service.run(request, correlationId(call)) }
                    val status = when (response.run.status) {
                        "FAILED" -> HttpStatusCode.BadGateway
                        else -> HttpStatusCode.OK
                    }
                    call.respond(status, response)
                } catch (failure: SourceServiceException) {
                    call.respond(HttpStatusCode.fromValue(failure.status), ApiError(failure.message, failure.hint))
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, ApiError("Source-learning run failed", "Check server logs and connector health; provider credentials are not returned to clients."))
                }
            }
        }
    }
}
