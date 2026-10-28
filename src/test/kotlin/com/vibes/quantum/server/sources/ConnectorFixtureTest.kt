package com.vibes.quantum.server.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.nio.charset.StandardCharsets

/** Deterministic connector tests; all API payloads are fixtures and no external service is contacted. */
class ConnectorFixtureTest {
    private class FixtureHttpClient : ConnectorHttpClient() {
        val requestedHosts = mutableListOf<String>()
        var requests = 0

        override fun get(
            url: String,
            allowedHost: String,
            headers: Map<String, String>,
            budget: SourceRequestBudget
        ): BoundedHttpResult {
            budget.reserveRequest()
            requests++
            requestedHosts += allowedHost
            val body = when (allowedHost) {
                "en.wikipedia.org" -> """{"pages":[{"id":1,"key":"Maize","title":"Maize","excerpt":"Maize is a cereal grain."}]}"""
                "api.search.brave.com" -> """{"web":{"results":[{"title":"Research result","url":"https://example.org/research","description":"A short search result description."}]}}"""
                "www.googleapis.com" -> """{"items":[{"id":{"videoId":"abc123"},"snippet":{"title":"Learning metadata","description":"A short metadata description.","channelTitle":"Example channel"}}]}"""
                "oauth.reddit.com" -> """{"data":{"children":[{"data":{"title":"Community opinion","permalink":"/r/test/comments/123/example/","selftext":"A short public discussion excerpt.","subreddit":"test"}}]}}"""
                else -> error("Unexpected test host $allowedHost")
            }
            val size = body.toByteArray(StandardCharsets.UTF_8).size.toLong()
            budget.recordBytes(size)
            return BoundedHttpResult(200, body, emptyMap(), size)
        }

        override fun postForm(
            url: String,
            allowedHost: String,
            headers: Map<String, String>,
            formBody: String,
            budget: SourceRequestBudget
        ): BoundedHttpResult {
            budget.reserveRequest()
            requests++
            requestedHosts += allowedHost
            assertTrue(headers.containsKey("Authorization"))
            val body = """{"access_token":"fixture-access-token","expires_in":3600,"token_type":"bearer"}"""
            val size = body.toByteArray(StandardCharsets.UTF_8).size.toLong()
            budget.recordBytes(size)
            return BoundedHttpResult(200, body, emptyMap(), size)
        }
    }

    @Test fun wikipediaBraveAndYoutubeParseOnlyExpectedMetadata() {
        val http = FixtureHttpClient()
        val query = SourceQuery("targeted evidence question", "MISSING_EVIDENCE")
        val budget = SourceRequestBudget(8, 512 * 1024)

        val wikipedia = WikipediaSourceConnector(http).search(query, budget).single()
        val brave = BraveSearchConnector(http, "test-brave-secret").search(query, budget).single()
        val youtube = YouTubeMetadataConnector(http, "test-youtube-secret").search(query, budget).single()

        assertEquals("EDUCATIONAL_REFERENCE", wikipedia.sourceType)
        assertTrue(wikipedia.sourceUrl.startsWith("https://en.wikipedia.org/wiki/"))
        assertEquals("SEARCH_SNIPPET", brave.sourceType)
        assertEquals("https://example.org/research", brave.sourceUrl)
        assertEquals("VIDEO_METADATA", youtube.sourceType)
        assertEquals("https://www.youtube.com/watch?v=abc123", youtube.sourceUrl)
        assertTrue(youtube.licenseNotes.contains("not a verified transcript"))
        assertEquals(3, http.requests)
        val storedMaterial = listOf(wikipedia, brave, youtube).joinToString(" ") { "${it.title} ${it.excerpt} ${it.licenseNotes}" }
        assertFalse(storedMaterial.contains("test-brave-secret"))
        assertFalse(storedMaterial.contains("test-youtube-secret"))
    }

    @Test fun httpClientRejectsNonHttpsAndNonAllowlistedHostsBeforeNetworkAccess() {
        val client = ConnectorHttpClient()
        val budget = SourceRequestBudget(maxRequests = 3, maxBytes = 1024)
        assertFailsWith<IllegalArgumentException> {
            client.get("http://en.wikipedia.org/w/rest.php/v1/search/page", "en.wikipedia.org", budget = budget)
        }
        assertFailsWith<IllegalArgumentException> {
            client.get("https://example.org/research", "en.wikipedia.org", budget = budget)
        }
        assertEquals(0, budget.requestCount())
    }

    @Test fun redditFixtureUsesOauthAndMarksResultsAsOpinion() {
        val http = FixtureHttpClient()
        val connector = RedditDiscussionConnector(http, "client-id", "client-secret")
        val result = connector.search(SourceQuery("targeted evidence question", "LOW_CONFIDENCE"), SourceRequestBudget(4, 512 * 1024)).single()
        assertEquals("PUBLIC_DISCUSSION_OPINION", result.sourceType)
        assertTrue(result.sourceUrl.startsWith("https://www.reddit.com/r/test/"))
        assertTrue(result.licenseNotes.contains("not established fact"))
        assertEquals(listOf("www.reddit.com", "oauth.reddit.com"), http.requestedHosts)
        assertFalse(result.excerpt.contains("fixture-access-token"))
    }

    @Test fun aConnectorCannotSilentlyProceedPastRequestBudget() {
        val connector = object : SourceConnector {
            override val id = "budget-fixture"
            override val name = "Budget fixture"
            override val configured = true
            override val description = "fixture"
            override fun search(query: SourceQuery, budget: SourceRequestBudget): List<ConnectorSource> {
                budget.reserveRequest()
                budget.reserveRequest()
                return emptyList()
            }
        }
        val service = SourceConnectorService(
            enabled = true,
            connectors = listOf(connector),
            store = SourceCandidateStore(null),
            maxRequestsPerRun = 1
        )
        val result = service.run(SourceRunRequest("targeted evidence question", "MISSING_EVIDENCE", listOf("budget-fixture")))
        assertEquals("PAUSED_BUDGET", result.run.status)
        assertEquals(1, result.run.requestCount)
        assertTrue(result.run.errors.any { it.contains("budget") })
    }

    @Test fun providerOutageIsRecordedWithoutExposingRawExceptionContent() {
        val connector = object : SourceConnector {
            override val id = "outage-fixture"
            override val name = "Outage fixture"
            override val configured = true
            override val description = "fixture"
            override fun search(query: SourceQuery, budget: SourceRequestBudget): List<ConnectorSource> {
                budget.reserveRequest()
                throw SourceHttpException(503, "authorization=top-secret")
            }
        }
        val service = SourceConnectorService(true, listOf(connector), SourceCandidateStore(null))
        val result = service.run(SourceRunRequest("targeted evidence question", "CONTRADICTION", listOf("outage-fixture")))
        assertEquals("FAILED", result.run.status)
        assertTrue(result.run.errors.any { it.contains("HTTP 503") })
        assertFalse(result.run.errors.toString().contains("top-secret"))
    }
}
