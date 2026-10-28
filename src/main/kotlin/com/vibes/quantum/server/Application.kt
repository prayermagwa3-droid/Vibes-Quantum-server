package com.vibes.quantum.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun main() {
    val configuredPort = System.getenv("PORT")?.toIntOrNull() ?: 8080
    require(configuredPort in 1..65535) { "PORT must be between 1 and 65535" }
    embeddedServer(
        factory = Netty,
        host = System.getenv("HOST")?.takeIf { it.isNotBlank() } ?: "0.0.0.0",
        port = configuredPort,
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        header("Content-Security-Policy", "default-src 'self'; base-uri 'self'; form-action 'self' https://accounts.google.com; frame-ancestors 'none'; object-src 'none'; img-src 'self' data: https://*.googleusercontent.com; style-src 'self' https://accounts.google.com/gsi/style; script-src 'self' https://accounts.google.com/gsi/client; connect-src 'self' https://accounts.google.com/gsi/ https://accounts.google.com; frame-src https://accounts.google.com/gsi/")
    }
    install(ContentNegotiation) {
        json(Json { prettyPrint = false; ignoreUnknownKeys = true })
    }
    val config = environment.config
    fun configured(name: String, fallback: String = ""): String =
        config.propertyOrNull("vibes.gateway.$name")?.getString()?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
    val allowedModels = configured("allowedModels", "")
        .split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    configureRouting(
        trustedProxies = parseTrustedProxies(configured("trustedProxies")),
        gatewayKey = configured("adminKey").takeIf { it.isNotEmpty() },
        registryFile = configured("registryFile", "data/vibes-apps.json"),
        apiKeysFile = configured("apiKeysFile", "data/vibes-api-keys.json"),
        upstreamBaseUrl = configured("upstreamBaseUrl", "https://api.openai.com/v1"),
        upstreamApiKey = configured("upstreamApiKey").takeIf { it.isNotEmpty() },
        upstreamModel = configured("upstreamModel", "unconfigured"),
        allowedModels = allowedModels,
        googleClientId = configured("googleClientId").takeIf { it.isNotEmpty() },
        sourceConnectorsEnabled = configured("sourceConnectorsEnabled", "false").equals("true", ignoreCase = true),
        sourceBraveApiKey = configured("sourceBraveApiKey").takeIf { it.isNotEmpty() },
        sourceYouTubeApiKey = configured("sourceYouTubeApiKey").takeIf { it.isNotEmpty() },
        sourceRedditClientId = configured("sourceRedditClientId").takeIf { it.isNotEmpty() },
        sourceRedditClientSecret = configured("sourceRedditClientSecret").takeIf { it.isNotEmpty() },
        sourceStoreFile = configured("sourceStoreFile", "data/vibes-source-learning.json"),
        sourceMaxRequestsPerRun = configured("sourceMaxRequestsPerRun", "8").toIntOrNull() ?: 8,
        sourceMaxBytesPerRun = configured("sourceMaxBytesPerRun", "524288").toLongOrNull() ?: 524_288L,
        sourceMaxCandidatesPerRun = configured("sourceMaxCandidatesPerRun", "15").toIntOrNull() ?: 15,
        curriculumStoreFile = configured("curriculumStoreFile", "data/vibes-curriculum.json"),
        learningStoreFile = configured("learningStoreFile", "data/vibes-learning.json")
    )
}
