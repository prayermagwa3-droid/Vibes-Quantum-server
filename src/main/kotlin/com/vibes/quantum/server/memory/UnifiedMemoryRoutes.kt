package com.vibes.quantum.server.memory

import com.vibes.quantum.server.MutationRateLimiter
import com.vibes.quantum.server.authorizeMutation
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.routing.delete
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** Admin-only management API. scopeId must be a pseudonymous, app-scoped value, never a raw user identifier. */
fun Route.unifiedMemoryRoutes(
    adminKey: String?,
    limiter: MutationRateLimiter,
    memory: UnifiedMemoryIndex,
    resolveUserId: (String) -> String?
) {
    fun ownerScope(owner: String): String = "user:" + MessageDigest.getInstance("SHA-256")
        .digest(owner.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(40)
    fun authenticatedOwner(call: ApplicationCall): String? {
        val authorization = call.request.headers["Authorization"]?.trim().orEmpty()
        if (!authorization.startsWith("Bearer ", ignoreCase = true)) return null
        val token = authorization.substringAfter(' ').trim().takeIf { it.isNotBlank() } ?: return null
        return resolveUserId(token)?.takeIf { it.isNotBlank() }
    }
    route("/api/v1/unified-memory") {
        get {
            val owner = authenticatedOwner(call) ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            if (!memory.configured()) return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Encrypted memory is not configured on this server"))
            try { call.response.headers.append("Cache-Control", "no-store", safeOnly = false); call.respond(memory.list(ownerScope(owner))) }
            catch (_: Exception) { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Memory unavailable; data was not reset")) }
        }
        get("/search") {
            val owner = authenticatedOwner(call) ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            if (!memory.configured()) return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Encrypted memory is not configured on this server"))
            val query = call.request.queryParameters["q"].orEmpty()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 8
            try { call.response.headers.append("Cache-Control", "no-store", safeOnly = false); call.respond(memory.search(ownerScope(owner), query, limit)) }
            catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (failure.message ?: "Invalid search request"))) }
            catch (_: Exception) { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Memory search unavailable")) }
        }
        post("/upsert") {
            val owner = authenticatedOwner(call) ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            if (!memory.configured()) return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Encrypted memory is not configured on this server"))
            val request = try { call.receive<UnifiedMemoryUserUpsertRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid memory request")); return@post
            }
            if (!request.consent) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Explicit consent is required to save long-term memory"))
            try {
                val saved = memory.upsert(UnifiedMemoryUpsertRequest(
                    scopeId = ownerScope(owner), content = request.content, kind = request.kind, tags = request.tags,
                    sourceType = "USER_PROVIDED", sourceReference = request.sourceReference,
                    confidence = request.confidence, userReviewed = request.userReviewed, expiresAt = request.expiresAt
                ))
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                call.respond(HttpStatusCode.Created, saved)
            } catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (failure.message ?: "Invalid memory request"))) }
            catch (_: Exception) { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Memory save failed; no success was recorded")) }
        }
        delete("/{id}") {
            val owner = authenticatedOwner(call) ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            if (!memory.configured()) return@delete call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Encrypted memory is not configured on this server"))
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Memory ID required"))
            try {
                val deleted = memory.forget(ownerScope(owner), id)
                call.respond(if (deleted) HttpStatusCode.OK else HttpStatusCode.NotFound, mapOf("deleted" to deleted, "recoverable" to false))
            } catch (_: Exception) { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Memory deletion unavailable")) }
        }
        post("/delete-all") {
            val owner = authenticatedOwner(call) ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            if (!memory.configured()) return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Encrypted memory is not configured on this server"))
            try { call.respond(UnifiedMemoryUserDeleteResult(memory.forgetScope(ownerScope(owner)))) }
            catch (_: Exception) { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Memory deletion unavailable")) }
        }
    }

    route("/api/v1/memory-index") {
        get("/stats") {
            if (!call.authorizeMutation(adminKey, limiter)) return@get
            call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
            call.respond(memory.stats())
        }
        get("/scope/{scopeId}") {
            if (!call.authorizeMutation(adminKey, limiter)) return@get
            if (!memory.configured()) return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Encrypted memory is not configured on this server"))
            val scopeId = call.parameters["scopeId"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing scopeId"))
            try {
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                call.respond(memory.list(scopeId))
            } catch (failure: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (failure.message ?: "Invalid scopeId")))
            }
        }
        get("/search") {
            if (!call.authorizeMutation(adminKey, limiter)) return@get
            if (!memory.configured()) return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Encrypted memory is not configured on this server"))
            val scopeId = call.request.queryParameters["scopeId"].orEmpty()
            val query = call.request.queryParameters["q"].orEmpty()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 8
            try {
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                call.respond(memory.search(scopeId, query, limit))
            } catch (failure: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (failure.message ?: "Invalid search request")))
            }
        }
        post("/upsert") {
            if (!call.authorizeMutation(adminKey, limiter)) return@post
            if (!memory.configured()) return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Encrypted memory is not configured on this server"))
            val request = try { call.receive<UnifiedMemoryUpsertRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid memory request")); return@post
            }
            try {
                call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
                call.respond(memory.upsert(request))
            } catch (failure: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (failure.message ?: "Invalid memory request")))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Memory persistence failed; no success was recorded"))
            }
        }
        post("/forget") {
            if (!call.authorizeMutation(adminKey, limiter)) return@post
            if (!memory.configured()) return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Encrypted memory is not configured on this server"))
            val request = try { call.receive<UnifiedMemoryForgetRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "scopeId and memoryId are required")); return@post
            }
            try {
                val deleted = memory.forget(request.scopeId, request.memoryId)
                call.respond(if (deleted) HttpStatusCode.OK else HttpStatusCode.NotFound, mapOf("deleted" to deleted))
            } catch (failure: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (failure.message ?: "Invalid deletion request")))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Memory deletion failed"))
            }
        }
        post("/forget-scope") {
            if (!call.authorizeMutation(adminKey, limiter)) return@post
            if (!memory.configured()) return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Encrypted memory is not configured on this server"))
            val request = try { call.receive<UnifiedMemoryForgetScopeRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "scopeId is required")); return@post
            }
            try {
                call.respond(UnifiedMemoryDeleteResult(memory.forgetScope(request.scopeId), request.scopeId))
            } catch (failure: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (failure.message ?: "Invalid scopeId")))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Scope deletion failed"))
            }
        }
    }
}
