package com.vibes.quantum.server.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

interface SourceConnector {
    val id: String
    val name: String
    val configured: Boolean
    val description: String
    fun search(query: SourceQuery, budget: SourceRequestBudget): List<ConnectorSource>
}

private val sourceJson = Json { ignoreUnknownKeys = true; isLenient = false }
private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
private fun JsonObject.str(name: String): String = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.obj(name: String): JsonObject? = runCatching { this[name]?.jsonObject }.getOrNull()
private fun JsonObject.array(name: String): JsonArray? = runCatching { this[name]?.jsonArray }.getOrNull()
private fun cleanExcerpt(value: String, limit: Int = 480): String = value
    .replace(Regex("<[^>]{0,200}>"), " ")
    .replace(Regex("&(?:nbsp|amp|quot|lt|gt);"), " ")
    .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim().take(limit)

class WikipediaSourceConnector(private val http: ConnectorHttpClient) : SourceConnector {
    override val id = "wikipedia"
    override val name = "Wikipedia API"
    override val configured = true
    override val description = "Official English Wikipedia search API; short excerpts only, verify article-specific license and attribution obligations"

    override fun search(query: SourceQuery, budget: SourceRequestBudget): List<ConnectorSource> {
        val url = "https://en.wikipedia.org/w/rest.php/v1/search/page?q=${enc(query.query)}&limit=5"
        val response = http.get(url, "en.wikipedia.org", budget = budget)
        val root = sourceJson.parseToJsonElement(response.body).jsonObject
        return root.array("pages").orEmpty().mapNotNull { element ->
            val page = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val title = cleanExcerpt(page.str("title"), 180)
            val key = page.str("key").ifBlank { title }
            val excerpt = cleanExcerpt(page.str("excerpt").ifBlank { page.str("description") })
            if (title.isBlank() || excerpt.isBlank()) return@mapNotNull null
            val path = enc(key.replace(' ', '_')).replace("+", "%20")
            ConnectorSource(
                sourceType = "EDUCATIONAL_REFERENCE",
                title = title,
                sourceUrl = "https://en.wikipedia.org/wiki/$path",
                excerpt = excerpt,
                publisher = "Wikipedia contributors",
                licenseNotes = "Wikipedia article text is generally under CC BY-SA/GFDL terms; verify the article/source-specific licensing notice. Attribution and share-alike obligations may apply."
            )
        }
    }
}

class BraveSearchConnector(
    private val http: ConnectorHttpClient,
    private val apiKey: String?
) : SourceConnector {
    override val id = "brave_search"
    override val name = "Brave Search API"
    override val configured: Boolean get() = !apiKey.isNullOrBlank()
    override val description = "Approved web search API; stores result metadata and short search snippets only"

    override fun search(query: SourceQuery, budget: SourceRequestBudget): List<ConnectorSource> {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Brave Search API is not configured")
        val url = "https://api.search.brave.com/res/v1/web/search?q=${enc(query.query)}&count=5&safesearch=moderate"
        val response = http.get(url, "api.search.brave.com", mapOf("X-Subscription-Token" to key), budget)
        val root = sourceJson.parseToJsonElement(response.body).jsonObject
        return root.obj("web")?.array("results").orEmpty().mapNotNull { element ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val title = cleanExcerpt(item.str("title"), 180)
            val urlValue = item.str("url")
            val excerpt = cleanExcerpt(item.str("description"))
            if (title.isBlank() || excerpt.isBlank() || !safeHttpsUrl(urlValue)) return@mapNotNull null
            ConnectorSource("SEARCH_SNIPPET", title, urlValue, excerpt, "Brave Search result", "Search snippet; follow the linked source's licence and terms before reuse.")
        }
    }
}

class YouTubeMetadataConnector(
    private val http: ConnectorHttpClient,
    private val apiKey: String?
) : SourceConnector {
    override val id = "youtube"
    override val name = "YouTube Data API"
    override val configured: Boolean get() = !apiKey.isNullOrBlank()
    override val description = "Video search metadata only; no video or full transcript downloads"

    override fun search(query: SourceQuery, budget: SourceRequestBudget): List<ConnectorSource> {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("YouTube Data API is not configured")
        val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=5&q=${enc(query.query)}&key=${enc(key)}"
        val response = http.get(url, "www.googleapis.com", budget = budget)
        val root = sourceJson.parseToJsonElement(response.body).jsonObject
        return root.array("items").orEmpty().mapNotNull { element ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val idValue = item.obj("id")?.str("videoId").orEmpty()
            val snippet = item.obj("snippet") ?: return@mapNotNull null
            val title = cleanExcerpt(snippet.str("title"), 180)
            val excerpt = cleanExcerpt(snippet.str("description"))
            val channel = cleanExcerpt(snippet.str("channelTitle"), 120)
            if (idValue.isBlank() || title.isBlank()) return@mapNotNull null
            ConnectorSource(
                sourceType = "VIDEO_METADATA",
                title = title,
                sourceUrl = "https://www.youtube.com/watch?v=$idValue",
                excerpt = excerpt,
                publisher = channel.ifBlank { "YouTube creator" },
                licenseNotes = "YouTube Data API metadata only. This is not a verified transcript or proof of the video's claims; retain source reference and respect YouTube terms."
            )
        }
    }
}

private fun safeHttpsUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)
}.getOrDefault(false)


/** Reddit results are explicitly classified as public discussion/opinion, never factual corroboration by themselves. */
class RedditDiscussionConnector(
    private val http: ConnectorHttpClient,
    private val clientId: String?,
    private val clientSecret: String?
) : SourceConnector {
    override val id = "reddit"
    override val name = "Reddit public discussions"
    override val configured: Boolean get() = !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()
    override val description = "OAuth-authenticated public discussions; opinion is not established fact; short excerpts only"

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L

    override fun search(query: SourceQuery, budget: SourceRequestBudget): List<ConnectorSource> {
        val token = getAccessToken(budget)
        val url = "https://oauth.reddit.com/search.json?q=${enc(query.query)}&limit=5&sort=relevance&type=link"
        val response = http.get(url, "oauth.reddit.com", mapOf("Authorization" to "Bearer $token"), budget)
        val root = sourceJson.parseToJsonElement(response.body).jsonObject
        return root.obj("data")?.array("children").orEmpty().mapNotNull { element ->
            val child = runCatching { element.jsonObject.obj("data") }.getOrNull() ?: return@mapNotNull null
            val title = cleanExcerpt(child.str("title"), 180)
            val permalink = child.str("permalink")
            if (title.isBlank() || !permalink.startsWith("/") || permalink.startsWith("//")) return@mapNotNull null
            val selfText = cleanExcerpt(child.str("selftext"), 320)
            val excerpt = cleanExcerpt(if (selfText.isBlank()) title else "$title — $selfText", 480)
            val subreddit = cleanExcerpt(child.str("subreddit"), 100).ifBlank { "public discussion" }
            ConnectorSource(
                sourceType = "PUBLIC_DISCUSSION_OPINION",
                title = title,
                sourceUrl = "https://www.reddit.com$permalink",
                excerpt = excerpt,
                publisher = "Reddit r/$subreddit",
                licenseNotes = "Public discussion/opinion, not established fact. Keep attribution, follow Reddit Data API terms, and do not infer factual truth from votes or repetition."
            )
        }
    }

    @Synchronized private fun getAccessToken(budget: SourceRequestBudget): String {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { it.isNotBlank() && now < tokenExpiresAt }?.let { return it }
        val id = clientId?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Reddit client ID is not configured")
        val secret = clientSecret?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Reddit client secret is not configured")
        val basic = Base64.getEncoder().encodeToString("$id:$secret".toByteArray(StandardCharsets.UTF_8))
        val tokenResponse = http.postForm(
            url = "https://www.reddit.com/api/v1/access_token",
            allowedHost = "www.reddit.com",
            headers = mapOf("Authorization" to "Basic $basic"),
            formBody = "grant_type=client_credentials",
            budget = budget
        )
        val tokenPayload = sourceJson.parseToJsonElement(tokenResponse.body).jsonObject
        val token = tokenPayload.str("access_token")
        val lifetime = tokenPayload["expires_in"]?.jsonPrimitive?.longOrNull ?: 0L
        if (token.isBlank() || lifetime <= 0L) throw IllegalStateException("Reddit OAuth returned an invalid token response")
        cachedToken = token
        tokenExpiresAt = now + (lifetime - 30L).coerceAtLeast(30L) * 1000L
        return token
    }
}
