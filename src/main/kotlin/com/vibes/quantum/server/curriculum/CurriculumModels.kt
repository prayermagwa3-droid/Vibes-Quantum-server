package com.vibes.quantum.server.curriculum

import kotlinx.serialization.Serializable

@Serializable
data class Curriculum(
    val id: String,
    val title: String,
    val domain: String,
    val objectives: List<String>,
    val prerequisiteIds: List<String> = emptyList(),
    val difficulty: String = "FOUNDATION",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: String,
    val updatedAt: String
)

/** Claims enter this store only through the authenticated trainer API with explicit review provenance. */
@Serializable
data class ApprovedLearningClaim(
    val id: String,
    val subject: String,
    val relation: String,
    val objectValue: String,
    val domain: String,
    val language: String = "en",
    val scope: String = "",
    val qualifiers: List<String> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val evidenceRefs: List<String>,
    val reviewedBy: String,
    val reviewedAt: String,
    val status: String = "APPROVED"
)

@Serializable
data class ClaimSubmission(
    val subject: String,
    val relation: String,
    val objectValue: String,
    val domain: String,
    val language: String = "en",
    val scope: String = "",
    val qualifiers: List<String> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val evidenceRefs: List<String>,
    val reviewedBy: String
)

@Serializable
data class CurriculumSubmission(
    val title: String,
    val domain: String,
    val objectives: List<String>,
    val prerequisiteIds: List<String> = emptyList(),
    val difficulty: String = "FOUNDATION",
    val enabled: Boolean = true,
    val sortOrder: Int = 0
)

@Serializable
data class GenerateQuestionsRequest(
    val curriculumId: String,
    val claimIds: List<String> = emptyList(),
    val language: String? = null,
    val limit: Int = 20
)

@Serializable
data class SyntheticQuestion(
    val id: String,
    val curriculumId: String,
    val sourceClaimId: String,
    val domain: String,
    val language: String,
    val sourceClaimLanguage: String = "en",
    val requestedLanguage: String,
    val questionType: String,
    val question: String,
    val expectedAnswer: String,
    val evidenceRefs: List<String>,
    val synthetic: Boolean = true,
    val independentEvidence: Boolean = false,
    val split: String = "PRACTICE",
    val status: String = "CANDIDATE",
    val languageReviewRequired: Boolean = false,
    val qualityRating: Int? = null,
    val trainerNote: String = "",
    val createdAt: String
)

@Serializable
data class QuestionReviewRequest(
    val action: String,
    val question: String? = null,
    val expectedAnswer: String? = null,
    val trainerNote: String = "",
    val qualityRating: Int? = null
)

@Serializable
data class CurriculumSnapshot(
    val curricula: List<Curriculum>,
    val approvedClaims: Int,
    val questions: List<SyntheticQuestion>,
    val candidateQuestions: Int,
    val reviewedExamplesByDomain: Map<String, Int>,
    val note: String = "Synthetic questions are practice material, not independent evidence. Evaluation holdouts are stored separately and are never generated into this practice set."
)

@Serializable
data class GeneratedQuestionsResponse(
    val curriculumId: String,
    val generated: Int,
    val skippedClaims: Int,
    val questions: List<SyntheticQuestion>,
    val note: String = "Questions are synthetic and remain candidates until trainer review. They do not increase factual confidence."
)

@Serializable
data class CurriculumError(val error: String, val hint: String? = null)

@Serializable
data class ApprovedClaimPage(val total: Int, val limit: Int, val offset: Int, val claims: List<ApprovedLearningClaim>)
