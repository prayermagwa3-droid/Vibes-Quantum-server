package com.vibes.quantum.server.curriculum

import java.time.Instant
import java.util.UUID

class CurriculumService(private val store: CurriculumStore, private val clock: () -> String = { Instant.now().toString() }) {
    fun snapshot() = store.snapshot()

    fun generate(input: GenerateQuestionsRequest): GeneratedQuestionsResponse {
        require(input.limit in 1..100) { "limit must be between 1 and 100" }
        val curriculum = store.findCurriculum(input.curriculumId) ?: throw IllegalArgumentException("Curriculum not found")
        require(curriculum.enabled) { "This curriculum is disabled" }
        val requestedLanguage = (input.language ?: "en").lowercase()
        require(requestedLanguage in CurriculumStore.LANGUAGES) { "language must be en, sn (Shona), or nd (Ndebele)" }
        val approved = store.allApprovedClaims().filter { it.domain.equals(curriculum.domain, ignoreCase = true) }
        val selected = if (input.claimIds.isEmpty()) approved else approved.filter { it.id in input.claimIds }
        require(selected.isNotEmpty()) { "No approved claims match this curriculum. Add reviewed claims with evidence references first." }
        val output = mutableListOf<SyntheticQuestion>()
        for (claim in selected) {
            if (output.size >= input.limit) break
            val languageNeedsReview = requestedLanguage != "en" || claim.language != requestedLanguage
            val language = requestedLanguage
            val templates = templates(claim, language)
            for ((type, question, answer) in templates) {
                if (output.size >= input.limit) break
                output += SyntheticQuestion(
                    id = UUID.randomUUID().toString(), curriculumId = curriculum.id, sourceClaimId = claim.id,
                    domain = claim.domain, language = language, sourceClaimLanguage = claim.language, requestedLanguage = language,
                    questionType = type, question = question, expectedAnswer = answer,
                    evidenceRefs = claim.evidenceRefs, synthetic = true, independentEvidence = false,
                    split = "PRACTICE", status = if (languageNeedsReview) "NEEDS_LANGUAGE_REVIEW" else "CANDIDATE",
                    languageReviewRequired = languageNeedsReview,
                    trainerNote = if (languageNeedsReview) "Native-speaker review or source-language consistency check is required before acceptance." else "",
                    createdAt = clock()
                )
            }
        }
        val saved = store.saveGenerated(output)
        val skipped = (selected.size - saved.map { it.sourceClaimId }.distinct().size).coerceAtLeast(0)
        return GeneratedQuestionsResponse(curriculum.id, saved.size, skipped, saved)
    }

    fun review(id: String, request: QuestionReviewRequest) = store.reviewQuestion(id, request)

    /** Returns only accepted practice examples; never includes candidate or holdout evaluation data. */
    fun fewShotExamples(domain: String, limit: Int = 12): List<SyntheticQuestion> {
        require(domain.trim().length in 1..80) { "domain must be 1–80 characters" }
        require(limit in 1..30) { "limit must be between 1 and 30" }
        return store.snapshot().questions.filter { it.status == "ACCEPTED" && (it.qualityRating ?: 0) >= 4 && it.domain.equals(domain.trim(), true) }
            .groupBy { it.questionType }.values.flatMap { group -> group.take(3) }.take(limit)
    }

    private fun templates(claim: ApprovedLearningClaim, language: String): List<Triple<String, String, String>> {
        val relation = claim.relation.trim()
        val statement = "${claim.subject} — $relation → ${claim.objectValue}."
        val scopeText = claim.scope.takeIf(String::isNotBlank)
        val exceptionText = claim.exceptions.takeIf(List<String>::isNotEmpty)?.joinToString("; ")
        val qualifiers = claim.qualifiers.takeIf(List<String>::isNotEmpty)?.joinToString("; ")
        val rows = mutableListOf<Triple<String, String, String>>()
        when (language) {
            "en" -> {
                rows += Triple("RELATIONSHIP", "According to the reviewed claim, what relationship is recorded for ${claim.subject}?", statement)
                rows += Triple("PARAPHRASE", "In the ${claim.domain} domain, what does the reviewed claim state about ${claim.subject}?", statement)
                rows += Triple("COUNTEREXAMPLE", "What kind of evidence could challenge this claim without assuming a contradiction already exists?", "A reliable, independent source that directly conflicts with the claim within the same scope. This question does not assert that such evidence exists.")
                rows += Triple("WHAT_IF", "Before applying this claim in a different context, what should be checked?", "Check the claim's scope, qualifiers, exceptions, and current supporting evidence; do not assume it applies universally.")
                if (scopeText != null) rows += Triple("SCOPE_CHECK", "What scope is recorded for this claim?", scopeText)
                if (exceptionText != null) rows += Triple("EXCEPTION_CHECK", "Which exceptions or limitations are recorded for this claim?", exceptionText)
                if (qualifiers != null) rows += Triple("QUALIFIER_CHECK", "Which qualifiers are recorded for this claim?", qualifiers)
            }
            "sn" -> {
                rows += Triple("RELATIONSHIP", "Maererano nechirevo chakaongororwa, hukama hupi huripo pamusoro pa ${claim.subject}?", statement)
                rows += Triple("PARAPHRASE", "Muchikamu che ${claim.domain}, chirevo chakaongororwa chinoti chii pamusoro pa ${claim.subject}?", statement)
                rows += Triple("COUNTEREXAMPLE", "Uchapupu hwemhando ipi hungapikisa chirevo ichi pasina kufungidzira kuti kupesana kuripo?", "Panodiwa manyuko akavimbika uye akazvimirira anopikisa chirevo ichi mukati memamiriro acho. Mubvunzo uyu haurevi kuti uchapupu hwakadaro huripo.")
                rows += Triple("WHAT_IF", "Usati washandisa chirevo ichi mune imwe mamiriro, chii chinofanira kuongororwa?", "Ongorora mamiriro acho, zvinowedzerwa, zvinosiya, uye uchapupu huripo; usafungidzira kuti chinoshanda kwose kwose.")
                if (scopeText != null) rows += Triple("SCOPE_CHECK", "Ndeapi mamiriro akanyorwa pachirevo ichi?", scopeText)
                if (exceptionText != null) rows += Triple("EXCEPTION_CHECK", "Ndezvipi zvisizvo kana zvinoganhurira zvakanyorwa pachirevo ichi?", exceptionText)
                if (qualifiers != null) rows += Triple("QUALIFIER_CHECK", "Ndezvipi zvinowedzerwa zvakanyorwa pachirevo ichi?", qualifiers)
            }
            "nd" -> {
                rows += Triple("RELATIONSHIP", "Ngokwesimangalo esihloliweyo, yibuphi ubudlelwano obubhalwe ngo ${claim.subject}?", statement)
                rows += Triple("PARAPHRASE", "Emkhakheni we ${claim.domain}, simangalo esihloliweyo sithini ngo ${claim.subject}?", statement)
                rows += Triple("COUNTEREXAMPLE", "Luphi uhlobo lobufakazi olungaphikisa isimangalo lesi ngaphandle kokucabanga ukuthi ukuphikisana sekukhona?", "Umthombo othembekileyo nozimeleyo ophikisana lesimangalo ngaphansi komkhawulo ofanayo. Umbuzo lo kawutsho ukuthi lobufakazi bukhona.")
                rows += Triple("WHAT_IF", "Ngaphambi kokusebenzisa isimangalo lesi kwesinye isimo, kuyini okumele kuhlolwe?", "Hlola umkhawulo wesimangalo, izengezo, okungafakiweyo, lobufakazi obukhona; ungacabangi ukuthi sisebenza kuzo zonke izimo.")
                if (scopeText != null) rows += Triple("SCOPE_CHECK", "Yisiphi isimo esibhalwe kulesi simangalo?", scopeText)
                if (exceptionText != null) rows += Triple("EXCEPTION_CHECK", "Yikuphi okungafakiweyo kumbe imikhawulo ebhaliweyo kulesi simangalo?", exceptionText)
                if (qualifiers != null) rows += Triple("QUALIFIER_CHECK", "Yiziphi izengezo ezibhalwe kulesi simangalo?", qualifiers)
            }
        }
        return rows
    }
}
