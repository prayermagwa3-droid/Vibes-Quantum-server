package com.vibes.quantum.server.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.util.Locale

@Serializable
data class DomainSafetyReview(
    val domain: String,
    val impact: String,
    val reviewRequired: Boolean,
    val citationCount: Int,
    val issues: List<String>,
    val professionalHelpGuidance: String? = null,
    val note: String = "Keyword detection is a safety cue, not reliable classification. Human review may still be necessary."
)

/** Read-only, config-driven domain safety profiles. New domains are added in JSON, not in the engine's branching logic. */
class DomainPolicyEngine(resourceName: String = "quality/domain-policies.json") {
    private val json = Json { ignoreUnknownKeys = false }
    private val root: JsonObject = run {
        val stream = DomainPolicyEngine::class.java.classLoader.getResourceAsStream(resourceName)
            ?: throw IllegalStateException("Domain policy resource is missing")
        stream.bufferedReader(Charsets.UTF_8).use { json.parseToJsonElement(it.readText()).jsonObject }
    }
    private val domains: JsonObject = root["domains"]?.jsonObject ?: error("Domain profiles are missing")
    private val defaultDomain: String = root["default_domain"]?.jsonPrimitive?.content ?: "general"

    private data class Selection(val id: String, val profile: JsonObject, val matches: List<String>)

    fun select(prompt: String, explicitDomain: String? = null): Pair<String, String> {
        val requested = explicitDomain?.trim()?.lowercase(Locale.ROOT)?.replace(' ', '_')
        if (requested != null && requested in domains) {
            return requested to instruction(requested, domains[requested]!!.jsonObject)
        }
        val normalized = normalize(prompt)
        val candidates = domains.entries.mapNotNull { (id, value) ->
            if (id == defaultDomain) return@mapNotNull null
            val profile = value as? JsonObject ?: return@mapNotNull null
            val terms = (profile["detection_terms"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
            val hits = terms.filter { term -> term.isNotBlank() && normalize(term) in normalized }
            if (hits.isEmpty()) null else Selection(id, profile, hits)
        }
        val picked = candidates.sortedWith(
            compareByDescending<Selection> { impactRank(it.profile["impact"]?.jsonPrimitive?.content.orEmpty()) }
                .thenByDescending { it.matches.size }
                .thenBy { it.id }
        ).firstOrNull()
        val id = picked?.id ?: defaultDomain
        val profile = picked?.profile ?: domains[defaultDomain]?.jsonObject ?: error("Default domain missing")
        return id to instruction(id, profile)
    }

    fun assess(domain: String, answer: String, evidence: List<CouncilEvidence>, jurisdiction: String? = null): DomainSafetyReview {
        val profile = domains[domain]?.jsonObject ?: domains[defaultDomain]?.jsonObject ?: error("Default domain missing")
        val impact = profile["impact"]?.jsonPrimitive?.content ?: "low"
        val requiredCitations = profile["require_citations"]?.jsonPrimitive?.contentOrNull == "true"
        val trustedHosts = (profile["trusted_evidence_hosts"] as? JsonArray).orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
        val reliableEvidence = evidence.filter { item -> isTrustedReference(item.url, trustedHosts) }
        val answerHasCitation = reliableEvidence.any { item -> answer.contains("[${item.id}]") || (item.url != null && answer.contains(item.url)) }
        val notes = mutableListOf<String>()
        if (requiredCitations && trustedHosts.isEmpty()) notes += "TRUSTED_EVIDENCE_HOSTS_NOT_CONFIGURED"
        if (requiredCitations && reliableEvidence.isEmpty()) notes += "RELIABLE_EVIDENCE_MISSING"
        else if (requiredCitations && !answerHasCitation) notes += "ANSWER_CITATIONS_MISSING"
        if (impact == "high") {
            if (!hasUncertaintyLanguage(answer)) notes += "UNCERTAINTY_OR_LIMITATIONS_NOT_EXPLICIT"
            if (!hasQualifiedHelpLanguage(domain, answer)) notes += "QUALIFIED_HELP_NOT_MENTIONED"
            if (domain == "legal" && jurisdiction.isNullOrBlank()) notes += "LEGAL_JURISDICTION_MISSING"
            else if (domain == "legal") notes += "LEGAL_SOURCE_JURISDICTION_MATCH_REQUIRES_REVIEW"
        }
        val professionalHelp = profile["professional_help"]?.jsonPrimitive?.contentOrNull
        return DomainSafetyReview(domain, impact, notes.isNotEmpty(), reliableEvidence.size, notes, professionalHelp)
    }

    fun directory(): List<DomainPolicySummary> = domains.entries.mapNotNull { (id, value) ->
        val profile = value as? JsonObject ?: return@mapNotNull null
        val trustedHosts = (profile["trusted_evidence_hosts"] as? JsonArray).orEmpty()
        DomainPolicySummary(
            id = id,
            impact = profile["impact"]?.jsonPrimitive?.contentOrNull ?: "low",
            requiresCitations = profile["require_citations"]?.jsonPrimitive?.contentOrNull == "true",
            trustedEvidenceHostsConfigured = trustedHosts.isNotEmpty(),
            professionalHelp = profile["professional_help"]?.jsonPrimitive?.contentOrNull,
            safetyNote = profile["safety_note"]?.jsonPrimitive?.contentOrNull
        )
    }.sortedWith(compareBy<DomainPolicySummary> { impactRank(it.impact) * -1 }.thenBy { it.id })

    fun instruction(domain: String, profile: JsonObject? = domains[domain]?.jsonObject): String {
        val selected = profile ?: domains[defaultDomain]?.jsonObject ?: error("Default domain missing")
        val impact = selected["impact"]?.jsonPrimitive?.content ?: "low"
        val safety = selected["safety_note"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val help = selected["professional_help"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return buildString {
            append("Use the configured '$domain' domain profile. Do not invent sources or credentials. ")
            if (impact == "high") {
                append("This is a high-impact topic: cite supplied evidence only when its URL host matches the configured trusted_evidence_hosts list, explicitly state uncertainty and limitations, distinguish general information from individualized advice, and recommend qualified help when appropriate. An empty host list means source reliability is not configured; do not imply citations are verified. ")
            }
            if (safety.isNotBlank()) append("Domain safety rule: $safety ")
            if (help.isNotBlank()) append("Professional-help guidance: $help. ")
            append("If the supplied evidence is insufficient, state that clearly and ask for appropriate context rather than guessing.")
        }
    }

    private fun isTrustedReference(value: String?, trustedHosts: List<String>): Boolean {
        if (value.isNullOrBlank() || trustedHosts.isEmpty()) return false
        return try {
            val uri = URI(value)
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
            uri.scheme.equals("https", true) && uri.userInfo == null && trustedHosts.any { configured ->
                val trusted = configured.lowercase(Locale.ROOT).trim().trimEnd('.')
                trusted.isNotEmpty() && (host == trusted || host.endsWith(".$trusted"))
            }
        } catch (_: Exception) { false }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun impactRank(value: String): Int = when (value.lowercase(Locale.ROOT)) { "high" -> 3; "medium" -> 2; else -> 1 }

    private fun hasUncertaintyLanguage(answer: String): Boolean = Regex(
        "(?i)\\b(may|might|could|possibly|uncertain|uncertainty|limited evidence|not enough evidence|not a diagnosis|general information|verify|limitations|possibly incomplete|may vary|zvinga|zvinogona|hazvina chokwadi|kungaba|kungenzeka|akusikho okuqinisekileyo)\\b"
    ).containsMatchIn(answer)

    private fun hasQualifiedHelpLanguage(domain: String, answer: String): Boolean {
        val pattern = when (domain) {
            "health" -> "(?i)\\b(doctor|clinician|health professional|pharmacist|emergency services|medical professional)\\b"
            "farming_chemicals" -> "(?i)\\b(agronomist|extension officer|product label|local agricultural authority|qualified professional)\\b"
            "finance" -> "(?i)\\b(licensed financial|qualified financial|financial professional|financial adviser|financial advisor)\\b"
            "legal" -> "(?i)\\b(lawyer|attorney|legal aid|qualified legal|legal professional)\\b"
            else -> "(?i)\\b(qualified professional|specialist|trusted leader|teacher)\\b"
        }
        return Regex(pattern).containsMatchIn(answer)
    }

}
