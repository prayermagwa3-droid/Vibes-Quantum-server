package com.vibes.quantum.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.application.call

/** Versioned language/domain metadata is served as read-only configuration; draft status travels with each translation. */
private object QualityMetadataCatalog {
    private val parser = Json { ignoreUnknownKeys = false }
    private fun load(resource: String): JsonElement {
        val stream = QualityMetadataCatalog::class.java.classLoader.getResourceAsStream("quality/$resource")
            ?: throw IllegalStateException("Missing quality metadata resource")
        return stream.bufferedReader(Charsets.UTF_8).use { parser.parseToJsonElement(it.readText()) }
    }
    val languages: JsonElement by lazy { load("languages.json") }
    val domains: JsonElement by lazy { load("domain-policies.json") }
    val glossary: JsonElement by lazy { load("glossary.json") }
}

fun Route.qualityMetadataRoutes() {
    route("/api/v1/quality") {
        get("/languages") {
            call.response.headers.append("Cache-Control", "public, max-age=60", safeOnly = false)
            call.respond(QualityMetadataCatalog.languages)
        }
        get("/domains") {
            call.response.headers.append("Cache-Control", "public, max-age=60", safeOnly = false)
            call.respond(QualityMetadataCatalog.domains)
        }
        get("/glossary") {
            call.response.headers.append("Cache-Control", "public, max-age=60", safeOnly = false)
            call.respond(QualityMetadataCatalog.glossary)
        }
    }
}
