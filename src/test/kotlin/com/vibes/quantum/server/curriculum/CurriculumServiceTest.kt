package com.vibes.quantum.server.curriculum

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CurriculumServiceTest {
    private fun setup(): Triple<CurriculumStore, CurriculumService, Curriculum> {
        val store = CurriculumStore(null) { "2026-01-01T00:00:00Z" }
        val curriculum = store.addCurriculum(CurriculumSubmission(
            title = "Plant foundations", domain = "agriculture",
            objectives = listOf("Identify evidence-backed plant relationships"), difficulty = "FOUNDATION"
        ))
        val claim = store.addApprovedClaim(ClaimSubmission(
            subject = "Maize", relation = "REQUIRES", objectValue = "sufficient water",
            domain = "agriculture", language = "en", scope = "during active growth",
            qualifiers = listOf("availability varies by growth stage"), exceptions = listOf("drought tolerance varies by variety"),
            evidenceRefs = listOf("https://example.org/maize-water"), reviewedBy = "trainer-test"
        ))
        check(claim.status == "APPROVED")
        return Triple(store, CurriculumService(store) { "2026-01-01T00:00:00Z" }, curriculum)
    }

    @Test fun generatedQuestionsAreSyntheticAndNotIndependentEvidence() {
        val (store, service, curriculum) = setup()
        val result = service.generate(GenerateQuestionsRequest(curriculum.id, language = "en", limit = 20))
        assertTrue(result.generated >= 4)
        assertTrue(result.questions.all { it.synthetic && !it.independentEvidence && it.split == "PRACTICE" })
        assertTrue(result.questions.all { it.status == "CANDIDATE" })
        assertTrue(result.questions.any { it.questionType == "EXCEPTION_CHECK" })
        assertEquals(result.generated, store.snapshot().candidateQuestions)
    }

    @Test fun unreviewedOrUnknownClaimCannotBeUsedToGenerate() {
        val (_, service, curriculum) = setup()
        assertFailsWith<IllegalArgumentException> {
            service.generate(GenerateQuestionsRequest(curriculum.id, claimIds = listOf("not-an-approved-claim")))
        }
    }

    @Test fun languageMismatchRequiresHumanReviewBeforeAcceptance() {
        val (_, service, curriculum) = setup()
        val result = service.generate(GenerateQuestionsRequest(curriculum.id, language = "sn", limit = 1))
        val question = result.questions.single()
        assertEquals("NEEDS_LANGUAGE_REVIEW", question.status)
        assertTrue(question.languageReviewRequired)
        assertFailsWith<IllegalArgumentException> { service.review(question.id, QuestionReviewRequest("ACCEPT")) }
        val accepted = service.review(question.id, QuestionReviewRequest("ACCEPT", trainerNote = "Reviewed by Shona speaker", qualityRating = 5))
        assertEquals("ACCEPTED", accepted.status)
        assertFalse(accepted.languageReviewRequired)
    }

    @Test fun acceptedExamplesAreAvailableForFewShotButCandidatesAreNot() {
        val (_, service, curriculum) = setup()
        val generated = service.generate(GenerateQuestionsRequest(curriculum.id, language = "en", limit = 1)).questions.single()
        assertTrue(service.fewShotExamples("agriculture").isEmpty())
        service.review(generated.id, QuestionReviewRequest("ACCEPT", trainerNote = "Reviewed", qualityRating = 5))
        val examples = service.fewShotExamples("agriculture")
        assertEquals(1, examples.size)
        assertEquals("ACCEPTED", examples.single().status)
    }

    @Test fun editsKeepSyntheticEvidenceAndPracticeSplitMetadata() {
        val (_, service, curriculum) = setup()
        val generated = service.generate(GenerateQuestionsRequest(curriculum.id, language = "en", limit = 1)).questions.single()
        val edited = service.review(generated.id, QuestionReviewRequest("EDIT", question = "What does the claim say about maize?", expectedAnswer = "Maize requires sufficient water.", trainerNote = "Clarified wording"))
        assertEquals("CANDIDATE", edited.status)
        assertTrue(edited.synthetic)
        assertFalse(edited.independentEvidence)
        assertEquals("PRACTICE", edited.split)
    }

    @Test fun repeatedGenerationDoesNotDuplicateExistingPracticeQuestions() {
        val (_, service, curriculum) = setup()
        val request = GenerateQuestionsRequest(curriculum.id, language = "en", limit = 20)
        val first = service.generate(request)
        assertTrue(first.generated > 0)
        val second = service.generate(request)
        assertEquals(0, second.generated)
    }

    @Test fun curriculumPrerequisitesCannotCreateCycles() {
        val store = CurriculumStore(null)
        val first = store.addCurriculum(CurriculumSubmission("First path", "science", listOf("Understand a basic relationship")))
        val second = store.addCurriculum(CurriculumSubmission("Second path", "science", listOf("Understand another relationship"), prerequisiteIds = listOf(first.id)))
        assertFailsWith<IllegalArgumentException> {
            store.updateCurriculum(first.id, CurriculumSubmission("First path", "science", listOf("Understand a basic relationship"), prerequisiteIds = listOf(second.id)))
        }
    }

    @Test fun unsupportedLanguageAndMissingEvidenceAreRejected() {
        val store = CurriculumStore(null)
        assertFailsWith<IllegalArgumentException> {
            store.addApprovedClaim(ClaimSubmission("A", "IS_A", "B", "science", "xx", evidenceRefs = listOf("https://example.org"), reviewedBy = "trainer"))
        }
        assertFailsWith<IllegalArgumentException> {
            store.addApprovedClaim(ClaimSubmission("A", "IS_A", "B", "science", evidenceRefs = emptyList(), reviewedBy = "trainer"))
        }
    }
}
