package com.vibes.quantum.server

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files

class ApplicationTest {
    private companion object {
        const val TEST_ADMIN_KEY = "vibes-test-admin-secret-0123456789-abcdef"
    }

    private fun Application.testGateway(
        key: String? = null,
        registryFile: String? = null,
        apiKeysFile: String? = null,
        upstreamApiKey: String? = null,
        googleClientId: String? = null,
        sourceConnectorsEnabled: Boolean = false
    ) {
        install(ContentNegotiation) { json(Json { prettyPrint = true; ignoreUnknownKeys = true }) }
        configureRouting(
            gatewayKey = key,
            registryFile = registryFile,
            apiKeysFile = apiKeysFile,
            upstreamBaseUrl = "https://api.example.test/v1",
            upstreamApiKey = upstreamApiKey,
            upstreamModel = "test-model",
            allowedModels = setOf("test-model"),
            googleClientId = googleClientId,
            sourceConnectorsEnabled = sourceConnectorsEnabled,
            sourceStoreFile = null,
            learningStoreFile = null
        )
    }

    @Test fun readinessIsSeparateAndNeverEchoesAdminCredentials() = testApplication {
        application { testGateway(TEST_ADMIN_KEY) }
        val response = client.get("/api/ready")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"ready\": true") || body.contains("\"ready\":true"))
        assertTrue(body.contains("gatewayAdminAuthentication"))
        assertTrue(!body.contains(TEST_ADMIN_KEY), "Readiness must not expose the gateway secret")
        assertEquals(HttpStatusCode.OK, client.get("/readyz").status)
    }

    @Test fun shortOrPlaceholderAdminSecretCannotAccessManagementRoutes() = testApplication {
        application { testGateway("short-test-key") }
        val health = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertTrue(health.bodyAsText().contains("at least 32 characters"))
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/ready").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/v1/keys").status)

        val registration = client.post("/api/v1/apps") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", "short-test-key")
            setBody("""{"appId":"weak-key-app","displayName":"Weak Key App","baseUrl":"https://example.com","capabilities":[],"version":"1.0.0"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, registration.status)
    }

    @Test fun placeholderAdminSecretIsNotTreatedAsConfigured() = testApplication {
        application { testGateway("REPLACE_WITH_OUTPUT_OF_OPENSSL_RAND_HEX_32") }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/ready").status)
    }

    @Test fun exampleOAuthAndProviderPlaceholdersDoNotEnableIntegrations() = testApplication {
        application {
            testGateway(
                TEST_ADMIN_KEY,
                upstreamApiKey = "replace-with-your-provider-key",
                googleClientId = "your-web-client-id.apps.googleusercontent.com"
            )
        }
        val googleConfig = client.get("/api/v1/auth/google/config").bodyAsText()
        assertTrue(googleConfig.contains("\"enabled\": false") || googleConfig.contains("\"enabled\":false"))
        val aiStatus = client.get("/api/v1/gateway/ai/status").bodyAsText()
        assertTrue(aiStatus.contains("\"enabled\": false") || aiStatus.contains("\"enabled\":false"))
        assertTrue(!aiStatus.contains("replace-with-your-provider-key"))
    }

    @Test fun oversizedJsonBodiesAreRejectedAcrossGatewayEndpoints() = testApplication {
        application { testGateway(TEST_ADMIN_KEY, googleClientId = "test-google-client.apps.example") }
        val oversized = "x".repeat(65_537)

        val appRegistration = client.post("/api/v1/apps") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(oversized)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, appRegistration.status)

        val apiKeyCreation = client.post("/api/v1/keys") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            setBody(oversized)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, apiKeyCreation.status)

        val googleSignIn = client.post("/api/v1/auth/google") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("Origin", "https://vibesquantum.duckdns.org")
            setBody(oversized)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, googleSignIn.status)
    }

    @Test fun healthEndpointReturnsPlatformIdentityAndRuntimeMetrics() = testApplication {
        application { module() }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Vibes Quantum"))
        assertTrue(body.contains("Vibes Vyron X"))
        assertTrue(body.contains("uptimeSeconds"))
        assertTrue(body.contains("registrationEnabled"))
        assertTrue(body.contains("registryMode"))
        assertTrue(body.contains("securityWarnings"))
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
    }

    @Test fun gatewayInfoAndStatsExposeFeatureMetadata() = testApplication {
        application { module() }
        val info = client.get("/api/v1/gateway/info")
        assertEquals(HttpStatusCode.OK, info.status)
        assertTrue(info.bodyAsText().contains("X-Vibes-Gateway-Key"))
        assertTrue(info.bodyAsText().contains("capability filtering"))
        assertTrue(info.bodyAsText().contains("atomic file persistence"))
        assertTrue(info.bodyAsText().contains("mutationLimitPerMinute"))
        val stats = client.get("/api/v1/gateway/stats")
        assertEquals(HttpStatusCode.OK, stats.status)
        assertTrue(stats.bodyAsText().contains("totalApps"))
        assertTrue(stats.bodyAsText().contains("capabilities"))
    }

    @Test fun appDiscoverySupportsPaginationAndRejectsInvalidFilters() = testApplication {
        application { module() }
        val response = client.get("/api/v1/apps?limit=2&offset=0")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"apps\""))
        assertTrue(response.bodyAsText().contains("\"total\""))
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/apps?status=unknown").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/apps?offset=-1").status)
    }

    @Test fun registrationRequiresKeyAndSupportsSearchHeartbeatAndDelete() = testApplication {
        application { testGateway(TEST_ADMIN_KEY) }
        val id = "gateway-test-app"
        val payload = """{"appId":"$id","displayName":"Gateway Test App","baseUrl":"https://example.com/test","capabilities":["search","notes"],"version":"2.3.1"}"""
        val denied = client.post("/api/v1/apps") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(payload)
        }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)

        val created = client.post("/api/v1/apps") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.Created, created.status)
        assertTrue(created.bodyAsText().contains("Gateway Test App"))

        val discovered = client.get("/api/v1/apps?q=Gateway%20Test&capability=search&status=online")
        assertEquals(HttpStatusCode.OK, discovered.status)
        assertTrue(discovered.bodyAsText().contains(id))
        assertTrue(discovered.bodyAsText().contains("\"total\": 1"))

        val heartbeat = client.put("/api/v1/apps/$id/heartbeat") {
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
        }
        assertEquals(HttpStatusCode.OK, heartbeat.status)
        assertTrue(heartbeat.bodyAsText().contains("lastSeenAt"))

        val removed = client.delete("/api/v1/apps/$id") { header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY) }
        assertEquals(HttpStatusCode.OK, removed.status)
        assertTrue(removed.bodyAsText().contains("\"removed\": true"))
    }

    @Test fun registrationRejectsUnsafeUrlsAndDuplicateCapabilities() = testApplication {
        application { testGateway(TEST_ADMIN_KEY) }
        val unsafeUrl = """{"appId":"unsafe-test","displayName":"Unsafe Test","baseUrl":"http://example.com","capabilities":[],"version":"1.0"}"""
        val response = client.post("/api/v1/apps") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            setBody(unsafeUrl)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)

        val duplicateCapabilities = """{"appId":"duplicate-test","displayName":"Duplicate Test","baseUrl":"https://example.com","capabilities":["search","SEARCH"],"version":"1.0"}"""
        val duplicateResponse = client.post("/api/v1/apps") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            setBody(duplicateCapabilities)
        }
        assertEquals(HttpStatusCode.BadRequest, duplicateResponse.status)
    }

    @Test fun homepageReturnsInteractiveDashboard() = testApplication {
        application { module() }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]?.contains("text/html") == true)
        val body = response.bodyAsText()
        assertTrue(body.contains("Vibes Quantum"))
        assertTrue(body.contains("appSearch"))
        assertTrue(body.contains("gateway/stats"))
    }

    @Test fun savedRegistrySurvivesASecondServerInstance() {
        val directory = Files.createTempDirectory("vibes-quantum-test-")
        val registry = directory.resolve("apps.json")
        try {
            testApplication {
                application { testGateway(TEST_ADMIN_KEY, registry.toString()) }
                val created = client.post("/api/v1/apps") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
                    setBody("""{"appId":"persist-test","displayName":"Persist Test","baseUrl":"https://example.com","capabilities":["sync"],"version":"1.0.0"}""")
                }
                assertEquals(HttpStatusCode.Created, created.status)
                assertTrue(Files.exists(registry))
                assertTrue(Files.readString(registry).contains("persist-test"))
            }
            testApplication {
                application { testGateway(TEST_ADMIN_KEY, registry.toString()) }
                val restored = client.get("/api/v1/apps/persist-test")
                assertEquals(HttpStatusCode.OK, restored.status)
                assertTrue(restored.bodyAsText().contains("Persist Test"))
            }
        } finally {
            Files.deleteIfExists(registry)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun registrationRejectsOverlongUrlsAndMutationRateLimiterIsReported() = testApplication {
        application { testGateway(TEST_ADMIN_KEY) }
        val overlongUrl = "https://example.com/" + "a".repeat(2_100)
        val response = client.post("/api/v1/apps") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            setBody("""{"appId":"url-too-long","displayName":"URL Too Long","baseUrl":"$overlongUrl","capabilities":[],"version":"1.0"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val info = client.get("/api/v1/gateway/info").bodyAsText()
        assertTrue(info.contains("mutationLimitPerMinute"))
    }
    @Test fun registryMutationsAreRateLimitedPerClient() = testApplication {
        application { testGateway(TEST_ADMIN_KEY) }
        var lastStatus: HttpStatusCode = HttpStatusCode.OK
        repeat(61) { index ->
            val response = client.delete("/api/v1/apps/missing-$index") {
                header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            }
            lastStatus = response.status
        }
        assertEquals(HttpStatusCode.TooManyRequests, lastStatus)
    }

    @Test fun appSpecificKeysAreShownOnceStoredAsDigestsBoundToAppAndRevocable() {
        val directory = Files.createTempDirectory("vibes-quantum-key-test-")
        val registry = directory.resolve("apps.json")
        val keyFile = directory.resolve("api-keys.json")
        var generatedToken = ""
        var generatedKeyId = ""
        try {
            testApplication {
                application { testGateway(TEST_ADMIN_KEY, registry.toString(), keyFile.toString()) }
                val denied = client.get("/api/v1/keys")
                assertEquals(HttpStatusCode.Unauthorized, denied.status)
                val created = client.post("/api/v1/keys") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
                    setBody("""{"name":"Vyron key","appId":"vibes-vyron-x","scopes":["apps:register","apps:heartbeat"]}""")
                }
                assertEquals(HttpStatusCode.Created, created.status)
                val body = created.bodyAsText()
                generatedToken = Regex(""""apiKey"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: error("API key was not returned")
                generatedKeyId = Regex(""""id"\s*:\s*"(key_[A-Za-z0-9]+)"""").find(body)?.groupValues?.get(1) ?: error("Key ID was not returned")
                assertTrue(generatedToken.startsWith("vq_live_"))
                assertTrue(Files.exists(keyFile))
                assertTrue(!Files.readString(keyFile).contains(generatedToken), "Plaintext app key must not be persisted")

                val payload = """{"appId":"vibes-vyron-x","displayName":"Vibes Vyron X","baseUrl":"https://vibesquantum.duckdns.org","capabilities":["assistant"],"version":"1.0.0"}"""
                val registered = client.post("/api/v1/apps") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header("X-Vibes-API-Key", generatedToken)
                    setBody(payload)
                }
                assertEquals(HttpStatusCode.Created, registered.status)
                val deniedCrossApp = client.post("/api/v1/apps") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header("X-Vibes-API-Key", generatedToken)
                    setBody("""{"appId":"another-vibes-app","displayName":"Another app","baseUrl":"https://example.com","capabilities":[],"version":"1.0.0"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, deniedCrossApp.status)
                val heartbeat = client.put("/api/v1/apps/vibes-vyron-x/heartbeat") {
                    header("X-Vibes-API-Key", generatedToken)
                }
                assertEquals(HttpStatusCode.OK, heartbeat.status)
                val revoked = client.delete("/api/v1/keys/$generatedKeyId") {
                    header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
                }
                assertEquals(HttpStatusCode.OK, revoked.status)
                val afterRevoke = client.put("/api/v1/apps/vibes-vyron-x/heartbeat") {
                    header("X-Vibes-API-Key", generatedToken)
                }
                assertEquals(HttpStatusCode.Unauthorized, afterRevoke.status)
            }
        } finally {
            Files.deleteIfExists(keyFile)
            Files.deleteIfExists(registry)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun clientAddressIgnoresForwardedHeadersUnlessThePeerIsATrustedProxy() {
        // Untrusted direct peer: a spoofed X-Forwarded-For must not choose the bucket.
        assertEquals("198.51.100.2", resolveClientAddress("198.51.100.2", "10.9.9.9", emptySet()))
        assertEquals("198.51.100.2", resolveClientAddress("198.51.100.2", "10.9.9.9", setOf("10.0.0.5")))
        // Trusted proxy: the right-most untrusted hop is the real client.
        assertEquals("203.0.113.9", resolveClientAddress("10.0.0.5", "192.0.2.1, 203.0.113.9", setOf("10.0.0.5")))
        // Chained trusted proxies are skipped from the right.
        assertEquals("203.0.113.9", resolveClientAddress("10.0.0.5", "203.0.113.9, 10.0.0.6", setOf("10.0.0.5", "10.0.0.6")))
        // Missing, blank, or empty headers fall back to the proxy address.
        assertEquals("10.0.0.5", resolveClientAddress("10.0.0.5", null, setOf("10.0.0.5")))
        assertEquals("10.0.0.5", resolveClientAddress("10.0.0.5", " , ", setOf("10.0.0.5")))
    }

    @Test fun trustedProxyListParsingIgnoresBlanksAndEmptyInput() {
        assertEquals(emptySet<String>(), parseTrustedProxies(null))
        assertEquals(emptySet<String>(), parseTrustedProxies(""))
        assertEquals(setOf("10.0.0.5", "10.0.0.6"), parseTrustedProxies(" 10.0.0.5, ,10.0.0.6 "))
    }

    @Test fun aiGatewayStatusIsPublicButModelDiscoveryRequiresScopedKey() = testApplication {
        application { testGateway(TEST_ADMIN_KEY) }
        val status = client.get("/api/v1/gateway/ai/status")
        assertEquals(HttpStatusCode.OK, status.status)
        assertTrue(status.bodyAsText().contains("streamingSupported"))
        assertTrue(status.bodyAsText().contains("test-model"))
        // The test server pretty-prints JSON, so compare without whitespace.
        assertTrue(status.bodyAsText().filterNot { it.isWhitespace() }.contains("\"enabled\":false"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models").status)

        val keyResponse = client.post("/api/v1/keys") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            setBody("""{"name":"Inference test","appId":"vibes-test-app","scopes":["ai:chat"]}""")
        }
        assertEquals(HttpStatusCode.Created, keyResponse.status)
        val token = Regex(""""apiKey"\s*:\s*"([^"]+)"""").find(keyResponse.bodyAsText())?.groupValues?.get(1)
            ?: error("API key was not returned")
        val modelList = client.get("/v1/models") { header("X-Vibes-API-Key", token) }
        assertEquals(HttpStatusCode.OK, modelList.status)
        assertTrue(modelList.bodyAsText().contains("test-model"))

        // The key is valid and scoped correctly; inference returns 503 because this test has no upstream secret.
        val chat = client.post("/v1/chat/completions") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-API-Key", token)
            setBody("""{"messages":[{"role":"user","content":"hello"}]}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, chat.status)
        assertTrue(chat.headers["X-Request-ID"].orEmpty().startsWith("vqreq_"))
    }

    @Test fun chatCompletionNormalizationPreservesStreamingFlagForUpstreamSse() {
        val body = """{"model":"test-model","stream":true,"messages":[{"role":"user","content":"hello"}]}"""
        val normalized = normalizeChatCompletionRequest(body, "test-model", setOf("test-model"))
        assertTrue(normalized.streaming)
        assertTrue(normalized.body.contains("\"stream\":true"))
        val standard = normalizeChatCompletionRequest(body.replace("true", "false", ignoreCase = false), "test-model", setOf("test-model"))
        assertTrue(!standard.streaming)
        assertTrue(standard.body.contains("\"stream\":false"))
    }

    @Test fun aiGatewayEnforcesChatScopeAndRejectsMalformedInput() = testApplication {
        application { testGateway(TEST_ADMIN_KEY, upstreamApiKey = "test-upstream-key") }
        val registryOnlyKey = client.post("/api/v1/keys") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            setBody("""{"name":"Registry only","appId":"vibes-test-app","scopes":["apps:register"]}""")
        }
        assertEquals(HttpStatusCode.Created, registryOnlyKey.status)
        val registryToken = Regex(""""apiKey"\s*:\s*"([^"]+)"""").find(registryOnlyKey.bodyAsText())?.groupValues?.get(1)
            ?: error("Registry API key was not returned")
        val denied = client.post("/v1/chat/completions") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-API-Key", registryToken)
            setBody("""{"messages":[{"role":"user","content":"hello"}]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)

        val chatKeyResponse = client.post("/api/v1/keys") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            setBody("""{"name":"Chat capable","appId":"vibes-test-app","scopes":["ai:chat"]}""")
        }
        val chatToken = Regex(""""apiKey"\s*:\s*"([^"]+)"""").find(chatKeyResponse.bodyAsText())?.groupValues?.get(1)
            ?: error("Chat API key was not returned")
        val oversized = client.post("/v1/chat/completions") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-API-Key", chatToken)
            setBody("""{"messages":[{"role":"user","content":"${"x".repeat(70_000)}"}]}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
    }


    @Test fun phase8LearningRequiresScopedKeysConsentAndEnforcesAppBoundExports() = testApplication {
        application { testGateway(TEST_ADMIN_KEY) }
        val policy = client.put("/api/v1/learning/apps/tenant-a/policy") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            setBody("""{"enabled":true,"allowedCategories":["rating","teaching_example"],"retentionDays":30,"dailyQuota":10,"policyVersion":"1"}""")
        }
        assertEquals(HttpStatusCode.OK, policy.status)
        val keyResponse = client.post("/api/v1/keys") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            setBody("""{"name":"Tenant feedback","appId":"tenant-a","scopes":["learning:feedback"]}""")
        }
        assertEquals(HttpStatusCode.Created, keyResponse.status)
        val token = Regex(""""apiKey"\s*:\s*"([^"]+)"""").find(keyResponse.bodyAsText())?.groupValues?.get(1)
            ?: error("Learning API key was not returned")
        val noConsent = client.post("/api/v1/learning/feedback") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-API-Key", token)
            setBody("""{"appId":"tenant-a","subjectRef":"subject-x","category":"rating","signalType":"rating","rating":"up"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, noConsent.status)
        val consent = client.post("/api/v1/learning/consent") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-API-Key", token)
            setBody("""{"appId":"tenant-a","subjectRef":"subject-x","granted":true,"categories":["rating"],"noticeVersion":"1"}""")
        }
        assertEquals(HttpStatusCode.OK, consent.status)
        val feedback = client.post("/api/v1/learning/feedback") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-API-Key", token)
            setBody("""{"appId":"tenant-a","subjectRef":"subject-x","category":"rating","signalType":"rating","rating":"up","idempotencyKey":"unit-test-rating-1"}""")
        }
        assertEquals(HttpStatusCode.Created, feedback.status)
        val crossTenant = client.post("/api/v1/learning/feedback") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-API-Key", token)
            setBody("""{"appId":"tenant-b","subjectRef":"subject-x","category":"rating","signalType":"rating","rating":"up"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, crossTenant.status)
        val exported = client.post("/api/v1/learning/subjects/export") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-API-Key", token)
            setBody("""{"appId":"tenant-a","subjectRef":"subject-x"}""")
        }
        assertEquals(HttpStatusCode.OK, exported.status)
        assertTrue(exported.bodyAsText().contains("subject-x") == false, "Raw subject references must not be stored in the export")
        val deleted = client.post("/api/v1/learning/subjects/delete") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-API-Key", token)
            setBody("""{"appId":"tenant-a","subjectRef":"subject-x"}""")
        }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertTrue(deleted.bodyAsText().contains("\"deleted\":true"))
    }

    @Test fun sourceConnectorHealthRequiresAdminAuthenticationAndNeverExposesCredentials() = testApplication {
        application { testGateway(TEST_ADMIN_KEY, sourceConnectorsEnabled = true) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/research/connectors").status)
        val response = client.get("/api/v1/research/connectors") { header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("wikipedia"))
        assertTrue(body.contains("brave_search"))
        assertTrue(!body.contains(TEST_ADMIN_KEY))
    }

    @Test fun sourceLearningIsOptInAndNeverRunsWhenDisabled() = testApplication {
        application { testGateway(TEST_ADMIN_KEY) }
        val response = client.post("/api/v1/research/runs") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            setBody("""{"query":"why are maize leaves yellow","reasonForResearch":"MISSING_EVIDENCE","connectorIds":["wikipedia"]}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("source learning is disabled", ignoreCase = true))
    }

    @Test fun sourceRunBodyLimitRejectsOversizedRequests() = testApplication {
        application { testGateway(TEST_ADMIN_KEY, sourceConnectorsEnabled = true) }
        val response = client.post("/api/v1/research/runs") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            setBody("{\"query\":\"${"x".repeat(70_000)}\",\"reasonForResearch\":\"MISSING_EVIDENCE\"}")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test fun sourceRunRequiresValidGapReasonBeforeAnyNetworkAccess() = testApplication {
        application { testGateway(TEST_ADMIN_KEY, sourceConnectorsEnabled = true) }
        val response = client.post("/api/v1/research/runs") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Vibes-Gateway-Key", TEST_ADMIN_KEY)
            setBody("""{"query":"why are maize leaves yellow","reasonForResearch":"collect everything","connectorIds":["wikipedia"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("MISSING_EVIDENCE"))
    }

}

