package com.vibes.quantum.server.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

@Serializable
data class LanguageProfile(
    val code: String,
    val name: String,
    @SerialName("native_name") val nativeName: String,
    val stage: String,
    @SerialName("metadata_supported") val metadataSupported: Boolean,
    @SerialName("search_aliases_supported") val searchAliasesSupported: Boolean,
    @SerialName("ui_localization_coverage") val uiLocalizationCoverage: Double,
    @SerialName("native_review_status") val nativeReviewStatus: String,
    @SerialName("reviewer_required_for_important_claims") val reviewerRequiredForImportantClaims: Boolean
)

@Serializable
data class LanguageCatalogSnapshot(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("default_language") val defaultLanguage: String = "en",
    val languages: List<LanguageProfile>,
    val aliases: Map<String, String> = emptyMap(),
    @SerialName("ui_labels") val uiLabels: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class LanguageResolution(
    val input: String,
    val code: String,
    val name: String,
    val nativeName: String,
    val stage: String,
    val uiLocalizationCoverage: Double,
    val nativeReviewStatus: String,
    val reviewerRequiredForImportantClaims: Boolean
)

/** Shared alias/metadata registry. UI coverage is catalog coverage, not translation accuracy. */
class LanguageCatalog(resourceName: String = "quality/languages.json") {
    private val json = Json { ignoreUnknownKeys = true }
    val catalog: LanguageCatalogSnapshot = run {
        val stream = LanguageCatalog::class.java.classLoader.getResourceAsStream(resourceName)
            ?: throw IllegalStateException("Language catalog resource is missing")
        stream.bufferedReader(Charsets.UTF_8).use { json.decodeFromString<LanguageCatalogSnapshot>(it.readText()) }
    }
    private val byCode = catalog.languages.associateBy { it.code.lowercase(Locale.ROOT) }
    private val normalizedAliases = catalog.aliases.entries.associate { normalize(it.key) to it.value.lowercase(Locale.ROOT) }

    fun supportedLanguages(): LanguageCatalogSnapshot = catalog

    fun resolve(input: String?): LanguageProfile? {
        if (input.isNullOrBlank()) return null
        val normalized = normalize(input)
        val code = normalizedAliases[normalized] ?: normalized.takeIf { it in byCode }
        if (code != null) return byCode[code]
        return catalog.languages.firstOrNull { normalize(it.name) == normalized || normalize(it.nativeName) == normalized }
    }

    fun resolvePublic(input: String): LanguageResolution? = resolve(input)?.let {
        LanguageResolution(input.trim().take(64), it.code, it.name, it.nativeName, it.stage,
            it.uiLocalizationCoverage, it.nativeReviewStatus, it.reviewerRequiredForImportantClaims)
    }

    fun searchLanguages(query: String): List<LanguageResolution> {
        val needle = normalize(query)
        if (needle.isBlank()) return emptyList()
        val matchedCodes = linkedSetOf<String>()
        catalog.languages.forEach { profile ->
            val namesMatch = normalize(profile.name).contains(needle) || normalize(profile.nativeName).contains(needle) || profile.code == needle
            if (namesMatch) matchedCodes += profile.code
        }
        normalizedAliases.filterKeys { it.contains(needle) }.values.forEach { matchedCodes += it }
        return matchedCodes.mapNotNull { code -> byCode[code]?.let { profile ->
            LanguageResolution(query.trim().take(64), profile.code, profile.name, profile.nativeName,
                profile.stage, profile.uiLocalizationCoverage, profile.nativeReviewStatus, profile.reviewerRequiredForImportantClaims)
        } }
    }

    fun searchMatches(query: String, entries: List<LanguageSearchEntry>, language: String? = null): List<LanguageSearchEntry> {
        val needle = normalize(query)
        if (needle.isBlank()) return emptyList()
        val languageCode = resolve(language)?.code
        return entries.filter { entry ->
            val localizedTitleMatch = entry.titles.any { (code, title) ->
                (languageCode == null || code == languageCode) && normalize(title).contains(needle)
            }
            localizedTitleMatch || entry.aliases.any { normalize(it).contains(needle) } ||
                (languageCode == null && entry.titles.values.any { normalize(it).contains(needle) })
        }
    }

    fun responseInstruction(input: String?): Pair<LanguageProfile?, String> {
        val profile = resolve(input)
        val instruction = when (profile?.code) {
            "sn" -> "Respond in ChiShona as best you can. Preserve domain-specific technical terms where translation is not reviewed. Never silently translate or replace source evidence. The Shona UI/catalog is a draft and important claims require native-speaker review."
            "nd" -> "Respond in isiNdebele as best you can. Preserve domain-specific technical terms where translation is not reviewed. Never silently translate or replace source evidence. The Ndebele UI/catalog is a draft and important claims require native-speaker review."
            "en" -> "Respond in English. Preserve original source-language evidence and explicitly label any machine translation."
            else -> "Match the user's language when reasonably clear. Preserve original source-language evidence and explicitly label any machine translation. Do not assume an unrecognized language has been reviewed."
        }
        return profile to instruction
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

@Serializable
data class LanguageSearchEntry(
    val id: String,
    val titles: Map<String, String> = emptyMap(),
    val aliases: List<String> = emptyList()
)


@Serializable
data class DomainPolicySummary(
    val id: String,
    val impact: String,
    val requiresCitations: Boolean,
    val trustedEvidenceHostsConfigured: Boolean,
    val professionalHelp: String? = null,
    val safetyNote: String? = null
)

/** Read-only public metadata endpoints; no user prompts, provider keys or stored evidence are returned. */
fun io.ktor.server.routing.Route.qualityLanguageRoutes(
    catalog: LanguageCatalog,
    domainPolicies: DomainPolicyEngine = DomainPolicyEngine()
) {
    get("/api/v1/quality/languages") {
        call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
        call.respond(catalog.supportedLanguages())
    }
    get("/api/v1/quality/languages/search") {
        call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
        val query = call.request.queryParameters["q"].orEmpty()
        if (query.length !in 1..120) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "q must be between 1 and 120 characters"))
            return@get
        }
        call.respond(catalog.searchLanguages(query))
    }
    get("/api/v1/quality/languages/resolve") {
        call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
        val language = call.request.queryParameters["language"].orEmpty()
        val resolved = catalog.resolvePublic(language)
        if (resolved == null) call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Supported language alias not found"))
        else call.respond(resolved)
    }
    get("/api/v1/quality/domains") {
        call.response.headers.append("Cache-Control", "no-store", safeOnly = false)
        call.respond(domainPolicies.directory())
    }
}
