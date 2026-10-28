package com.vibes.quantum.server.agents

import com.vibes.quantum.server.ApiError
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.server.application.install

/** Workspace access is a capability issued by the server, not a client-supplied role claim. */
data class WorkspaceAuthorization(val appId: String?, val gatewayAdmin: Boolean, val scopes: Set<String>)

fun Route.workspaceEngineeringRoutes(
    service: WorkspaceEngineeringService,
    authorize: suspend (ApplicationCall) -> WorkspaceAuthorization?
) {
    route("/api/v1/workspace/jobs") {
        get {
            val access = authorize(call) ?: return@get
            call.response.headers.append(HttpHeaders.CacheControl, "no-store", safeOnly = false)
            call.respond(service.listJobs(access.appId, access.gatewayAdmin))
        }

        post {
            val access = authorize(call) ?: return@post
            val request = try { call.receive<WorkspaceCreateRequest>() }
            catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid workspace request", "Send targetApp, goal, continuous, and maxIterations as JSON."))
                return@post
            }
            if (request.continuous && !access.gatewayAdmin && "workspace:continuous" !in access.scopes) {
                call.respond(HttpStatusCode.Forbidden, ApiError("Continuous workspace mode is not authorized", "Issue an app-bound key with workspace:continuous; the app's local role alone is not a server credential."))
                return@post
            }
            try {
                val ownerAppId = access.appId ?: "gateway-admin"
                call.response.headers.append(HttpHeaders.CacheControl, "no-store", safeOnly = false)
                call.respond(HttpStatusCode.Created, service.createJob(ownerAppId, request))
            } catch (failure: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError(failure.message ?: "Invalid workspace request"))
            } catch (failure: IllegalStateException) {
                call.respond(HttpStatusCode.Conflict, ApiError(failure.message ?: "Workspace capacity reached"))
            }
        }

        route("/{jobId}/archive") {
            install(RequestBodyLimit) { bodyLimit { MAX_ARCHIVE_BYTES + 1024L } }
            put {
                val access = authorize(call) ?: return@put
                val id = call.parameters["jobId"].orEmpty()
                val incomingType = call.request.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
                if (incomingType !in setOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) {
                    call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("Project upload must be a ZIP archive", "Use Content-Type: application/zip."))
                    return@put
                }
                try {
                    val input = call.receiveStream()
                    val result = withContext(Dispatchers.IO) { service.attachArchive(id, access.appId, access.gatewayAdmin, input) }
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store", safeOnly = false)
                    call.respond(HttpStatusCode.Accepted, result)
                } catch (_: PayloadTooLargeException) {
                    call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Project ZIP exceeds the upload limit", "Keep source ZIP archives under 64 MiB."))
                } catch (failure: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, ApiError(failure.message ?: "Workspace job not found"))
                } catch (failure: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(failure.message ?: "Invalid project ZIP"))
                } catch (failure: IllegalStateException) {
                    call.respond(HttpStatusCode.Conflict, ApiError(failure.message ?: "Workspace upload state conflict"))
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadGateway, ApiError("Workspace upload failed safely", "Check server logs and job status; uploaded code was not executed by the upload handler."))
                }
            }
        }

        get("/{jobId}") {
            val access = authorize(call) ?: return@get
            val id = call.parameters["jobId"].orEmpty()
            val view = service.getJob(id, access.appId, access.gatewayAdmin)
            if (view == null) call.respond(HttpStatusCode.NotFound, ApiError("Workspace job not found or access denied"))
            else {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store", safeOnly = false)
                call.respond(view)
            }
        }

        post("/{jobId}/stop") {
            val access = authorize(call) ?: return@post
            val id = call.parameters["jobId"].orEmpty()
            val current = service.getJob(id, access.appId, access.gatewayAdmin)
            if (current == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("Workspace job not found or access denied"))
                return@post
            }
            if (current.continuous && !access.gatewayAdmin && "workspace:continuous" !in access.scopes) {
                call.respond(HttpStatusCode.Forbidden, ApiError("Stopping this continuous job is not authorized", "Use a trusted admin credential or an app-bound key with workspace:continuous."))
                return@post
            }
            val view = service.stopJob(id, access.appId, access.gatewayAdmin)
            if (view == null) call.respond(HttpStatusCode.NotFound, ApiError("Workspace job not found or access denied"))
            else {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store", safeOnly = false)
                call.respond(view)
            }
        }

        get("/{jobId}/candidate.zip") {
            val access = authorize(call) ?: return@get
            val id = call.parameters["jobId"].orEmpty()
            val candidate = service.candidateZip(id, access.appId, access.gatewayAdmin)
            if (candidate == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("Candidate ZIP is not ready", "Wait for the workspace pipeline to package its staged candidate."))
                return@get
            }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store", safeOnly = false)
            call.response.headers.append(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "vibes-$id-candidate.zip").toString(), safeOnly = false)
            call.respondFile(candidate.toFile())
        }

        get("/{jobId}/artifacts.zip") {
            val access = authorize(call) ?: return@get
            val id = call.parameters["jobId"].orEmpty()
            val artifacts = service.buildArtifactsZip(id, access.appId, access.gatewayAdmin)
            if (artifacts == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("Build artifacts are not ready", "Run the baseline/candidate build step first. Logs may be available inline even when no APK was produced."))
                return@get
            }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store", safeOnly = false)
            call.response.headers.append(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "vibes-$id-build-artifacts.zip").toString(), safeOnly = false)
            call.respondFile(artifacts.toFile())
        }
    }

    route("/api/v1/workspace/brain") {
        get {
            val access = authorize(call) ?: return@get
            if (!access.gatewayAdmin && "workspace:run" !in access.scopes) {
                call.respond(HttpStatusCode.Forbidden, ApiError("Workspace brain is not authorized", "Use an app-bound key with workspace:run."))
                return@get
            }
            val target = call.request.queryParameters["targetApp"].orEmpty().trim().take(120)
            if (target.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Target app required", "Provide targetApp as a query parameter."))
                return@get
            }
            val brain = service.getBrain(access.appId, access.gatewayAdmin, target)
            if (brain == null) call.respond(HttpStatusCode.Forbidden, ApiError("Workspace brain access denied"))
            else {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store", safeOnly = false)
                call.respond(brain)
            }
        }
        post("/reflect") {
            val access = authorize(call) ?: return@post
            if (!access.gatewayAdmin && "workspace:run" !in access.scopes) {
                call.respond(HttpStatusCode.Forbidden, ApiError("Brain reflection is not authorized", "Use an app-bound key with workspace:run."))
                return@post
            }
            val request = try { call.receive<WorkspaceBrainReflectRequest>() }
            catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid brain reflection request", "Send targetApp as JSON."))
                return@post
            }
            if (request.targetApp.isBlank() || request.targetApp.length > 120) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid target app", "targetApp must contain 1 to 120 characters."))
                return@post
            }
            val brain = service.reflectBrain(access.appId, access.gatewayAdmin, request.targetApp)
            if (brain == null) call.respond(HttpStatusCode.Forbidden, ApiError("Brain reflection access denied"))
            else {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store", safeOnly = false)
                call.respond(brain)
            }
        }
    }

    route("/api/v1/workspace/supervisor") {
        get {
            val access = authorize(call) ?: return@get
            if (!access.gatewayAdmin) {
                call.respond(HttpStatusCode.Forbidden, ApiError("Supervisor access is admin-only", "Use an authenticated gateway-admin session."))
                return@get
            }
            try { call.respond(WorkspaceControlService().status()) }
            catch (failure: Exception) { call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Supervisor status unavailable", failure.message ?: "Control ledger unavailable.")) }
        }
        post("/configure") {
            val access = authorize(call) ?: return@post
            if (!access.gatewayAdmin) { call.respond(HttpStatusCode.Forbidden, ApiError("Supervisor configuration is admin-only")); return@post }
            val request = try { call.receive<WorkspaceSupervisorConfigRequest>() } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Invalid supervisor configuration")); return@post
            }
            try { call.respond(WorkspaceControlService().configure(request.intervalSeconds, request.maxCycles, request.failureThreshold, request.budgetUsd, request.maxSeconds)) }
            catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, ApiError("Supervisor configuration rejected", failure.message ?: "Invalid limits.")) }
            catch (failure: Exception) { call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Supervisor configuration unavailable", failure.message ?: "Control ledger unavailable.")) }
        }
        post("/{action}") {
            val access = authorize(call) ?: return@post
            if (!access.gatewayAdmin) { call.respond(HttpStatusCode.Forbidden, ApiError("Supervisor controls are admin-only")); return@post }
            try {
                val control = WorkspaceControlService()
                val result = when (call.parameters["action"]) {
                    "start" -> control.start()
                    "stop" -> control.stop()
                    "emergency-stop" -> control.emergencyStop()
                    "clear-emergency" -> control.clearEmergencyStop()
                    else -> { call.respond(HttpStatusCode.NotFound, ApiError("Unknown supervisor action")); return@post }
                }
                call.response.headers.append(HttpHeaders.CacheControl, "no-store", safeOnly = false)
                call.respond(result)
            } catch (failure: IllegalArgumentException) { call.respond(HttpStatusCode.Conflict, ApiError("Supervisor action rejected", failure.message ?: "Operation rejected.")) }
            catch (failure: Exception) { call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Supervisor control unavailable", failure.message ?: "Control ledger unavailable.")) }
        }
    }

}

private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
