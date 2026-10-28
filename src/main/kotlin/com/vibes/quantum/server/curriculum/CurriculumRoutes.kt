package com.vibes.quantum.server.curriculum

import com.vibes.quantum.server.MutationRateLimiter
import com.vibes.quantum.server.authorizeMutation
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.application.install

fun Route.curriculumRoutes(gatewayKey: String?, service: CurriculumService, store: CurriculumStore, limiter: MutationRateLimiter) {
    route("/api/v1/curriculum") {
        get {
            if (!call.authorizeMutation(gatewayKey, limiter)) return@get
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(service.snapshot())
        }
        get("/claims") {
            if (!call.authorizeMutation(gatewayKey, limiter)) return@get
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            if (limit !in 1..100 || offset !in 0..100_000) {
                call.respond(HttpStatusCode.BadRequest, CurriculumError("Invalid pagination", "limit must be 1–100 and offset 0–100000")); return@get
            }
            val claims = store.allApprovedClaims()
            call.respond(ApprovedClaimPage(claims.size, limit, offset, claims.drop(offset).take(limit)))
        }
        get("/examples") {
            if (!call.authorizeMutation(gatewayKey, limiter)) return@get
            val domain = call.request.queryParameters["domain"].orEmpty()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 12
            try { call.respond(service.fewShotExamples(domain, limit)) }
            catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, CurriculumError(failure.message ?: "Invalid request")) }
        }
        install(RequestBodyLimit) { bodyLimit { 65_536L } }
        post("/curricula") {
            if (!call.authorizeMutation(gatewayKey, limiter)) return@post
            val body = call.receiveBody<CurriculumSubmission>() ?: return@post
            try { call.respond(HttpStatusCode.Created, store.addCurriculum(body)) }
            catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, CurriculumError(failure.message ?: "Invalid curriculum")) }
        }
        put("/curricula/{id}") {
            if (!call.authorizeMutation(gatewayKey, limiter)) return@put
            val id = call.parameters["id"]?.takeIf { it.length in 1..100 }
            if (id == null) { call.respond(HttpStatusCode.BadRequest, CurriculumError("Invalid curriculum id")); return@put }
            val body = call.receiveBody<CurriculumSubmission>() ?: return@put
            try { call.respond(store.updateCurriculum(id, body)) }
            catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, CurriculumError(failure.message ?: "Invalid curriculum")) }
        }
        post("/claims") {
            if (!call.authorizeMutation(gatewayKey, limiter)) return@post
            val body = call.receiveBody<ClaimSubmission>() ?: return@post
            try { call.respond(HttpStatusCode.Created, store.addApprovedClaim(body)) }
            catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, CurriculumError(failure.message ?: "Invalid claim")) }
        }
        post("/generate") {
            if (!call.authorizeMutation(gatewayKey, limiter)) return@post
            val body = call.receiveBody<GenerateQuestionsRequest>() ?: return@post
            try { call.respond(HttpStatusCode.Created, service.generate(body)) }
            catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, CurriculumError(failure.message ?: "Could not generate questions")) }
            catch (failure: IllegalStateException) { call.respond(HttpStatusCode.Conflict, CurriculumError(failure.message ?: "Could not save questions")) }
        }
        post("/questions/{id}/review") {
            if (!call.authorizeMutation(gatewayKey, limiter)) return@post
            val id = call.parameters["id"]?.takeIf { it.length in 1..100 }
            if (id == null) { call.respond(HttpStatusCode.BadRequest, CurriculumError("Invalid question id")); return@post }
            val body = call.receiveBody<QuestionReviewRequest>() ?: return@post
            try { call.respond(service.review(id, body)) }
            catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, CurriculumError(failure.message ?: "Could not review question")) }
        }
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveBody(): T? = try {
    receive<T>()
} catch (_: PayloadTooLargeException) {
    respond(HttpStatusCode.PayloadTooLarge, CurriculumError("Request is too large", "Keep the JSON body below 65,536 bytes.")); null
} catch (_: Exception) {
    respond(HttpStatusCode.BadRequest, CurriculumError("Invalid JSON request", "Check the required fields and data types.")); null
}
