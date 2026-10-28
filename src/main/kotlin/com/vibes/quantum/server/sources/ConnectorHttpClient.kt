package com.vibes.quantum.server.sources

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class SourceBudgetExceeded(message: String) : RuntimeException(message)
class SourceHttpException(val status: Int, message: String) : RuntimeException(message)

/** Per-run budget: every attempt counts, and response bytes are measured from the body stream. */
class SourceRequestBudget(val maxRequests: Int, val maxBytes: Long) {
    private var requests = 0
    private var bytes = 0L
    @Synchronized fun reserveRequest() {
        if (requests >= maxRequests) throw SourceBudgetExceeded("Per-run request budget reached")
        requests += 1
    }
    @Synchronized fun recordBytes(count: Long) {
        val safeCount = count.coerceAtLeast(0L)
        if (safeCount > maxBytes - bytes) throw SourceBudgetExceeded("Per-run byte budget reached")
        bytes += safeCount
    }
    @Synchronized fun requestCount(): Int = requests
    @Synchronized fun bytesRead(): Long = bytes
    @Synchronized fun remainingBytes(): Long = maxBytes - bytes
}

data class BoundedHttpResult(val status: Int, val body: String, val headers: Map<String, List<String>>, val bytesRead: Long)

/** HTTPS-only client. Each connector must pass its own exact, fixed official host allowlist. */
open class ConnectorHttpClient(
    private val connectTimeout: Duration = Duration.ofSeconds(3),
    private val requestTimeout: Duration = Duration.ofSeconds(5),
    private val maxResponseBytes: Int = 256 * 1024,
    private val maxRetries: Int = 1,
    private val userAgent: String = "VibesQuantumResearch/1.0 (manual, budgeted evidence search)"
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val lastRequestByHost = ConcurrentHashMap<String, Long>()

    open fun get(
        url: String,
        allowedHost: String,
        headers: Map<String, String> = emptyMap(),
        budget: SourceRequestBudget
    ): BoundedHttpResult {
        val uri = validateUri(url, allowedHost)
        var lastFailure: Exception? = null
        for (attempt in 0..maxRetries) {
            budget.reserveRequest()
            try {
                throttle(uri.host)
                val builder = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET()
                headers.forEach { (name, value) ->
                    require(name.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Invalid connector header name" }
                    require(value.length <= 4096 && value.none { it == '\r' || it == '\n' }) { "Invalid connector header value" }
                    builder.header(name, value)
                }
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                val body = response.body().use { input -> readBounded(input, budget) }
                if ((response.statusCode() == 429 || response.statusCode() in 500..599) && attempt < maxRetries) {
                    pauseForRetry(attempt, response.headers().firstValue("Retry-After").orElse(null))
                    continue
                }
                if (response.statusCode() in 300..399) throw SourceHttpException(response.statusCode(), "Redirect refused by source connector policy")
                if (response.statusCode() !in 200..299) throw SourceHttpException(response.statusCode(), "Source returned HTTP ${response.statusCode()}")
                return BoundedHttpResult(response.statusCode(), body.first, response.headers().map(), body.second)
            } catch (failure: SourceBudgetExceeded) {
                throw failure
            } catch (failure: SourceHttpException) {
                if ((failure.status == 429 || failure.status in 500..599) && attempt < maxRetries) {
                    lastFailure = failure
                    pauseForRetry(attempt, null)
                    continue
                }
                throw failure
            } catch (failure: Exception) {
                lastFailure = failure
                if (attempt >= maxRetries) break
                pauseForRetry(attempt, null)
            }
        }
        throw SourceHttpException(0, "Source request failed after bounded retries: ${lastFailure?.javaClass?.simpleName ?: "network error"}")
    }

    open fun postForm(
        url: String,
        allowedHost: String,
        headers: Map<String, String>,
        formBody: String,
        budget: SourceRequestBudget
    ): BoundedHttpResult {
        val uri = validateUri(url, allowedHost)
        require(formBody.length <= 16_384 && formBody.none { it.isISOControl() && it !in "\r\n\t" }) { "Invalid form body" }
        var lastFailure: Exception? = null
        for (attempt in 0..maxRetries) {
            budget.reserveRequest()
            try {
                throttle(uri.host)
                val builder = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", userAgent)
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                headers.forEach { (name, value) ->
                    require(name.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Invalid connector header name" }
                    require(value.length <= 4096 && value.none { it == '\r' || it == '\n' }) { "Invalid connector header value" }
                    builder.header(name, value)
                }
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                val body = response.body().use { input -> readBounded(input, budget) }
                if ((response.statusCode() == 429 || response.statusCode() in 500..599) && attempt < maxRetries) {
                    pauseForRetry(attempt, response.headers().firstValue("Retry-After").orElse(null))
                    continue
                }
                if (response.statusCode() in 300..399) throw SourceHttpException(response.statusCode(), "Redirect refused by source connector policy")
                if (response.statusCode() !in 200..299) throw SourceHttpException(response.statusCode(), "Source returned HTTP ${response.statusCode()}")
                return BoundedHttpResult(response.statusCode(), body.first, response.headers().map(), body.second)
            } catch (failure: SourceBudgetExceeded) {
                throw failure
            } catch (failure: SourceHttpException) {
                if ((failure.status == 429 || failure.status in 500..599) && attempt < maxRetries) {
                    lastFailure = failure
                    pauseForRetry(attempt, null)
                    continue
                }
                throw failure
            } catch (failure: Exception) {
                lastFailure = failure
                if (attempt >= maxRetries) break
                pauseForRetry(attempt, null)
            }
        }
        throw SourceHttpException(0, "Source request failed after bounded retries: ${lastFailure?.javaClass?.simpleName ?: "network error"}")
    }

    private fun throttle(host: String) {
        // A conservative minimum gap keeps accidental repeated manual runs from hammering a provider.
        val now = System.currentTimeMillis()
        val waitMillis = synchronized(lastRequestByHost) {
            val previous = lastRequestByHost[host]
            val wait = if (previous == null) 0L else (350L - (now - previous)).coerceAtLeast(0L)
            lastRequestByHost[host] = now
            wait
        }
        if (waitMillis > 0) {
            try { Thread.sleep(waitMillis) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw SourceHttpException(0, "Source request interrupted") }
        }
    }

    private fun validateUri(value: String, allowedHost: String): URI {
        require(value.length <= 4096 && value.none { it.isISOControl() }) { "Invalid source URL" }
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS source URLs are allowed" }
        require(uri.host?.equals(allowedHost, ignoreCase = true) == true) { "Connector attempted to contact a non-allowlisted host" }
        require(uri.userInfo == null && (uri.port == -1 || uri.port == 443) && uri.fragment == null) { "Unsafe source URL" }
        return uri
    }

    private fun readBounded(input: java.io.InputStream, budget: SourceRequestBudget): Pair<String, Long> {
        val initialRemaining = budget.remainingBytes()
        if (initialRemaining <= 0L) throw SourceBudgetExceeded("Per-run byte budget reached")
        val limit = minOf(maxResponseBytes.toLong(), initialRemaining)
        val output = ByteArrayOutputStream(minOf(limit, 8192L).toInt())
        val buffer = ByteArray(4096)
        var total = 0L
        while (true) {
            val allowance = limit - total
            if (allowance <= 0L) {
                budget.recordBytes(total)
                throw SourceBudgetExceeded("Source response reached the per-response or per-run byte budget")
            }
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), allowance).toInt())
            if (read < 0) break
            total += read
            output.write(buffer, 0, read)
        }
        budget.recordBytes(total)
        return output.toString(Charsets.UTF_8) to total
    }

    private fun pauseForRetry(attempt: Int, retryAfter: String?) {
        val requested = retryAfter?.toLongOrNull()?.coerceIn(0L, 2L)
        val delay = requested?.times(1000L) ?: (100L shl attempt.coerceIn(0, 4))
        try { Thread.sleep(delay) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw SourceHttpException(0, "Source request interrupted") }
    }
}
