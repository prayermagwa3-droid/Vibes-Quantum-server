package com.vibes.quantum.server.quality

import com.vibes.quantum.server.MutationRateLimiter
import com.vibes.quantum.server.authorizeMutation
import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode

fun Route.qualityMemoryRoutes(adminKey:String?, limiter:MutationRateLimiter, quality:QualityGateService, memory:MemoryLifecycleService) {
 route("/api/v1/quality") {
  get("/history") { if(!call.authorizeMutation(adminKey,limiter)) return@get; call.respond(quality.history()) }
  get("/reviews") { if(!call.authorizeMutation(adminKey,limiter)) return@get; call.respond(quality.reviewQueue()) }
  post("/evaluate") { if(!call.authorizeMutation(adminKey,limiter)) return@post
   val request=try { call.receive<EvalRunRequest>() } catch(_:Exception) { call.respond(HttpStatusCode.BadRequest,mapOf("error" to "Invalid evaluation request")); return@post }
   val result=try { quality.evaluate(request) } catch(e:IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest,mapOf("error" to (e.message ?: "Invalid evaluation"))); return@post }
   call.respond(if(result.promoted) HttpStatusCode.OK else HttpStatusCode.Conflict,result)
  }
 }
 route("/api/v1/memory-lifecycle") {
  get("/claims") { if(!call.authorizeMutation(adminKey,limiter)) return@get; call.respond(memory.list()) }
  get("/policies") { if(!call.authorizeMutation(adminKey,limiter)) return@get; call.respond(memory.policyDirectory()) }
  post("/claims") { if(!call.authorizeMutation(adminKey,limiter)) return@post
   val c=try { call.receive<MemoryClaim>() } catch(_:Exception) { call.respond(HttpStatusCode.BadRequest,mapOf("error" to "Invalid claim")); return@post }
   try { call.respond(memory.upsert(c)) } catch(e:IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest,mapOf("error" to (e.message ?: "Invalid claim"))) }
  }
  post("/preview") { if(!call.authorizeMutation(adminKey,limiter)) return@post
   val req=try { call.receive<MaintenanceRequest>() } catch(_:Exception) { MaintenanceRequest() }
   try { call.respond(memory.preview(req.storageQuotaRecords)) } catch(e:IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest,mapOf("error" to (e.message ?: "Invalid quota"))) }
  }
  post("/maintain") { if(!call.authorizeMutation(adminKey,limiter)) return@post
   val req=try { call.receive<MaintenanceRequest>() } catch(_:Exception) { call.respond(HttpStatusCode.BadRequest,mapOf("error" to "Preview confirmation is required")); return@post }
   try { call.respond(memory.maintain(req)) } catch(e:IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest,mapOf("error" to (e.message ?: "Invalid maintenance request"))) }
  }
  post("/restore/{id}") { if(!call.authorizeMutation(adminKey,limiter)) return@post
   val id=call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest,mapOf("error" to "Missing claim id"))
   val restored=memory.restore(id); if(restored==null) call.respond(HttpStatusCode.NotFound,mapOf("error" to "No soft-deleted claim")) else call.respond(restored)
  }
  get("/versions/{id}") { if(!call.authorizeMutation(adminKey,limiter)) return@get; call.respond(memory.versions(call.parameters["id"] ?: "")) }
 }
}
