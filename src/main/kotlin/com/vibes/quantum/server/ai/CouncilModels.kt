package com.vibes.quantum.server.ai

import kotlinx.serialization.Serializable

@Serializable
data class CouncilEvidence(
    val id: String,
    val text: String,
    val title: String? = null,
    val url: String? = null,
    val publisher: String? = null,
    val retrievedAt: String? = null,
    /** Preserve the original source language; translations are separate, explicitly labeled metadata. */
    val sourceLanguage: String? = null,
    val translationKind: String? = null,
    val translationLanguage: String? = null,
    val translationReviewStatus: String? = null
)

@Serializable
data class CandidateAnswer(
    val id: String,
    val text: String,
    val providerId: String? = null,
    val evidenceIds: List<String> = emptyList()
)

@Serializable
data class CouncilRequest(
    val prompt: String,
    val mode: String = "COUNCIL",
    val enabled: Boolean = true,
    val evidence: List<CouncilEvidence> = emptyList(),
    val candidates: List<CandidateAnswer> = emptyList(),
    /** Only context intentionally supplied for this request. No global or unrelated memory is automatically attached. */
    val priorContext: List<String> = emptyList(),
    val selectedMembers: List<String> = emptyList(),
    val maxMembers: Int = 6,
    val maxCostUsd: Double = 0.05,
    val onlineBoost: Boolean = false,
    val includeMemberSummaries: Boolean = true,
    /** Optional domain plugin key, e.g. health, education or legal. Keyword routing is config-driven fallback only. */
    val domain: String? = null,
    /** Required for legal guidance when provided; never inferred from language alone. */
    val jurisdiction: String? = null,
    /** Optional language code or alias (English, ChiShona/Shona, isiNdebele/Ndebele). */
    val language: String? = null
)

@Serializable
data class CouncilMemberResult(
    val memberId: String,
    val role: String,
    val status: String,
    val providerId: String? = null,
    val summary: String? = null,
    val evidenceIds: List<String> = emptyList(),
    val warning: String? = null
)

@Serializable
data class EvidenceProvenance(
    val id: String,
    val title: String? = null,
    val url: String? = null,
    val publisher: String? = null,
    val duplicateOf: String? = null,
    val sourceLanguage: String? = null,
    val translationKind: String? = null,
    val translationLanguage: String? = null,
    val translationReviewStatus: String? = null
)

@Serializable
data class NormalizedClaim(
    val candidateId: String,
    val providerId: String? = null,
    val proposition: String,
    val normalized: String,
    val evidenceIds: List<String> = emptyList()
)

@Serializable
data class CouncilResponse(
    val answer: String,
    val mode: String,
    val status: String,
    val confidence: String,
    val progress: List<String>,
    val members: List<CouncilMemberResult>,
    val agreements: List<String>,
    val disagreements: List<String>,
    val unsupportedOrUnverified: List<String>,
    val evidence: List<EvidenceProvenance>,
    val independentProviderCount: Int,
    val estimatedCostUsd: Double,
    val normalizedClaims: List<NormalizedClaim> = emptyList(),
    val sharedSourceNotes: List<String> = emptyList(),
    val domain: String = "general",
    val domainReviewRequired: Boolean = false,
    val domainSafetyNotes: List<String> = emptyList(),
    val languageCode: String = "auto",
    val languageStage: String = "not_specified",
    val languageReviewRequired: Boolean = false,
    val languageQualityNotes: List<String> = emptyList(),
    val note: String = "Confidence is qualitative, not a calibrated probability. Model reviews are not independent verification unless grounded in separately sourced evidence."
)

@Serializable
data class CouncilRoleInfo(val id: String, val name: String, val purpose: String, val defaultEnabled: Boolean)
