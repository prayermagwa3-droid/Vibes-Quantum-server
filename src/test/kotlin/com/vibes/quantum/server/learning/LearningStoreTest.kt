package com.vibes.quantum.server.learning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class LearningStoreTest {
    private fun enabledStore(vararg appIds: String): LearningStore = LearningStore(null).also { store ->
        appIds.forEach { id ->
            store.updatePolicy(id, LearningPolicyUpdate(enabled = true, allowedCategories = listOf("rating", "task_outcome", "error_category", "teaching_example"), dailyQuota = 10))
        }
    }

    @Test
    fun `tenant boundaries keep same subject reference isolated by app`() {
        val store = enabledStore("vibes-a", "vibes-b")
        store.setConsent("vibes-a", "user-42", true, listOf("rating"), "1")
        store.setConsent("vibes-b", "user-42", true, listOf("rating"), "1")
        store.addFeedback(LearningFeedbackRequest("vibes-a", "user-42", "rating", "rating", rating = "up"))
        store.addFeedback(LearningFeedbackRequest("vibes-b", "user-42", "rating", "rating", rating = "down"))
        assertEquals(1, store.page("vibes-a", 10, 0).first)
        assertEquals(1, store.page("vibes-b", 10, 0).first)
        assertTrue(store.exportSubject("vibes-a", "user-42").feedback.single().rating == "up")
    }

    @Test
    fun `feedback requires both app policy and category-specific user consent`() {
        val store = LearningStore(null)
        store.updatePolicy("vibes-a", LearningPolicyUpdate(enabled = true, allowedCategories = listOf("rating")))
        assertFailsWith<IllegalArgumentException> {
            store.addFeedback(LearningFeedbackRequest("vibes-a", "user-42", "rating", "rating", rating = "up"))
        }
        store.setConsent("vibes-a", "user-42", true, listOf("rating"), "1")
        assertFailsWith<IllegalArgumentException> {
            store.addFeedback(LearningFeedbackRequest("vibes-a", "user-42", "task_outcome", "task_outcome", taskOutcome = "success"))
        }
    }

    @Test
    fun `opt out deletes retained per-subject feedback`() {
        val store = enabledStore("vibes-a")
        store.setConsent("vibes-a", "subject-x", true, listOf("rating"), "1")
        store.addFeedback(LearningFeedbackRequest("vibes-a", "subject-x", "rating", "rating", rating = "up"))
        val (_, deleted) = store.setConsent("vibes-a", "subject-x", false, emptyList(), "1")
        assertEquals(1, deleted)
        assertTrue(store.exportSubject("vibes-a", "subject-x").feedback.isEmpty())
        assertFalse(store.consentStatus("vibes-a", "subject-x")!!.consented)
    }

    @Test
    fun `idempotency key prevents duplicate feedback from inflating counts`() {
        val store = enabledStore("vibes-a")
        store.setConsent("vibes-a", "subject-x", true, listOf("rating"), "1")
        val request = LearningFeedbackRequest("vibes-a", "subject-x", "rating", "rating", rating = "up", idempotencyKey = "rating-event-01")
        val first = store.addFeedback(request)
        val second = store.addFeedback(request)
        assertFalse(first.duplicate)
        assertTrue(second.duplicate)
        assertEquals(first.id, second.id)
        assertEquals(1, store.summary("vibes-a").retainedSignals)
    }

    @Test
    fun `explicit teaching examples remain pending review`() {
        val store = enabledStore("vibes-a")
        store.setConsent("vibes-a", "subject-x", true, listOf("teaching_example"), "1")
        val accepted = store.addFeedback(LearningFeedbackRequest(
            appId = "vibes-a", subjectRef = "subject-x", category = "teaching_example", signalType = "teaching_example",
            teachingExample = "A deliberately submitted example for review."
        ))
        assertEquals("PENDING_REVIEW", accepted.status)
        assertEquals(1, store.summary("vibes-a").pendingTeachingExamples)
    }


    @Test
    fun `changing consent relevant policy requires current version consent again`() {
        val store = LearningStore(null)
        store.updatePolicy("vibes-a", LearningPolicyUpdate(enabled = true, allowedCategories = listOf("rating"), policyVersion = "1"))
        store.setConsent("vibes-a", "subject-x", true, listOf("rating"), "1")
        assertFailsWith<IllegalArgumentException> {
            store.updatePolicy("vibes-a", LearningPolicyUpdate(enabled = true, allowedCategories = listOf("rating"), retentionDays = 10, policyVersion = "1"))
        }
        store.updatePolicy("vibes-a", LearningPolicyUpdate(enabled = true, allowedCategories = listOf("rating"), retentionDays = 10, policyVersion = "2"))
        assertFailsWith<LearningConsentRequired> {
            store.addFeedback(LearningFeedbackRequest("vibes-a", "subject-x", "rating", "rating", rating = "up"))
        }
        store.setConsent("vibes-a", "subject-x", true, listOf("rating"), "2")
        assertTrue(store.addFeedback(LearningFeedbackRequest("vibes-a", "subject-x", "rating", "rating", rating = "up")).accepted)
    }

    @Test
    fun `new idempotency keys cannot bypass repeated identical rating suppression`() {
        val store = enabledStore("vibes-a")
        store.setConsent("vibes-a", "subject-x", true, listOf("rating"), "1")
        val first = store.addFeedback(LearningFeedbackRequest("vibes-a", "subject-x", "rating", "rating", rating = "up", idempotencyKey = "rating-event-01"))
        val second = store.addFeedback(LearningFeedbackRequest("vibes-a", "subject-x", "rating", "rating", rating = "up", idempotencyKey = "rating-event-02"))
        assertFalse(first.duplicate)
        assertTrue(second.duplicate)
        assertEquals(1, store.summary("vibes-a").retainedSignals)
    }

    @Test
    fun `subject deletion clears consent and submitted signals`() {
        val store = enabledStore("vibes-a")
        store.setConsent("vibes-a", "subject-x", true, listOf("rating"), "1")
        store.addFeedback(LearningFeedbackRequest("vibes-a", "subject-x", "rating", "rating", rating = "up"))
        assertEquals(1, store.deleteSubject("vibes-a", "subject-x"))
        assertTrue(store.exportSubject("vibes-a", "subject-x").feedback.isEmpty())
        assertEquals(null, store.consentStatus("vibes-a", "subject-x"))
    }
}
