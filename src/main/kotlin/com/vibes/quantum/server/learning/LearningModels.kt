package com.vibes.quantum.server.learning

import kotlinx.serialization.Serializable

@Serializable
data class LearningPolicy(
    val appId: String,
    val enabled: Boolean = false,
    val allowedCategories: List<String> = listOf("rating", "task_outcome", "error_category", "teaching_example"),
    val retentionDays: Int = 30,
    val dailyQuota: Int = 500,
    val updatedAt: String,
    val policyVersion: String = "1"
)

@Serializable
data class LearningPolicyUpdate(
    val enabled: Boolean = false,
    val allowedCategories: List<String> = listOf("rating", "task_outcome", "error_category", "teaching_example"),
    val retentionDays: Int = 30,
    val dailyQuota: Int = 500,
    val policyVersion: String = "1"
)

@Serializable
data class LearningConsentRequest(
    val appId: String,
    val subjectRef: String,
    val granted: Boolean,
    val categories: List<String> = emptyList(),
    val noticeVersion: String = "1"
)

@Serializable
data class LearningConsentStatus(
    val appId: String,
    val consented: Boolean,
    val categories: List<String>,
    val noticeVersion: String,
    val updatedAt: String,
    val deletedSignals: Int = 0
)

@Serializable
data class LearningFeedbackRequest(
    val appId: String,
    val subjectRef: String,
    val category: String,
    val signalType: String,
    val rating: String? = null,
    val taskOutcome: String? = null,
    val errorCategory: String? = null,
    val teachingExample: String? = null,
    val idempotencyKey: String? = null
)

@Serializable
data class LearningFeedbackRecord(
    val id: String,
    val appId: String,
    val subjectHash: String,
    val category: String,
    val signalType: String,
    val rating: String? = null,
    val taskOutcome: String? = null,
    val errorCategory: String? = null,
    val teachingExample: String? = null,
    val status: String,
    val createdAt: String,
    val expiresAt: String,
    val idempotencyDigest: String? = null
)

@Serializable
data class LearningFeedbackAccepted(
    val accepted: Boolean,
    val duplicate: Boolean = false,
    val id: String? = null,
    val status: String = "RECORDED",
    val note: String = "Feedback is tenant-scoped; it does not silently update global knowledge."
)

@Serializable
data class LearningFeedbackPage(
    val appId: String,
    val count: Int,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val feedback: List<LearningFeedbackRecord>
)

@Serializable
data class LearningSummary(
    val appId: String,
    val enabled: Boolean,
    val configuredCategories: List<String>,
    val retainedSignals: Int,
    val pendingTeachingExamples: Int,
    val signalsToday: Int,
    val dailyQuota: Int,
    val consentedSubjects: Int,
    val updatedAt: String
)

@Serializable
data class LearningSubjectExport(
    val appId: String,
    val consent: LearningConsentStatus? = null,
    val feedback: List<LearningFeedbackRecord>,
    val exportedAt: String,
    val note: String = "Only retained data for this app-scoped subject reference is included. Derived aggregate counters may not be reversible."
)

@Serializable
data class LearningError(val error: String, val hint: String? = null)

internal val ALLOWED_LEARNING_CATEGORIES = setOf("rating", "task_outcome", "error_category", "teaching_example")
internal val ALLOWED_SIGNAL_TYPES = setOf("rating", "task_outcome", "error_category", "teaching_example")
