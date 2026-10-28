package com.vibes.quantum.server.curriculum

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/** Small bounded JSON store; practice examples and evidence claims are kept distinct. */
class CurriculumStore(fileName: String? = "data/vibes-curriculum.json", private val clock: () -> String = { Instant.now().toString() }) {
    private val path: Path? = fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }
    private var curricula = mutableListOf<Curriculum>()
    private var claims = mutableListOf<ApprovedLearningClaim>()
    private var questions = mutableListOf<SyntheticQuestion>()

    init { load() }

    @Synchronized fun snapshot(): CurriculumSnapshot = CurriculumSnapshot(
        curricula = curricula.sortedWith(compareBy<Curriculum> { it.sortOrder }.thenBy { it.createdAt }), approvedClaims = claims.count { it.status == "APPROVED" },
        questions = questions.toList().asReversed().take(500), candidateQuestions = questions.count { it.status == "CANDIDATE" || it.status == "NEEDS_LANGUAGE_REVIEW" },
        reviewedExamplesByDomain = questions.filter { it.status == "ACCEPTED" }.groupingBy { it.domain }.eachCount()
    )
    @Synchronized fun findCurriculum(id: String): Curriculum? = curricula.firstOrNull { it.id == id }
    @Synchronized fun findClaim(id: String): ApprovedLearningClaim? = claims.firstOrNull { it.id == id && it.status == "APPROVED" }
    @Synchronized fun allApprovedClaims(): List<ApprovedLearningClaim> = claims.filter { it.status == "APPROVED" }.toList()
    @Synchronized fun findQuestion(id: String): SyntheticQuestion? = questions.firstOrNull { it.id == id }

    @Synchronized fun addCurriculum(input: CurriculumSubmission): Curriculum {
        validateText(input.title, "title", 2, 120); validateText(input.domain, "domain", 2, 80)
        require(input.objectives.isNotEmpty() && input.objectives.size <= 30) { "Provide 1–30 learning objectives" }
        require(input.objectives.all { it.trim().length in 3..300 }) { "Each objective must be 3–300 characters" }
        require(input.difficulty in DIFFICULTIES) { "difficulty must be one of ${DIFFICULTIES.joinToString()}" }
        require(input.prerequisiteIds.size <= 20 && input.prerequisiteIds.all { id -> curricula.any { it.id == id } }) { "Every prerequisite must reference an existing curriculum" }
        val now = clock()
        val item = Curriculum(UUID.randomUUID().toString(), input.title.trim(), input.domain.trim(), input.objectives.map(String::trim), input.prerequisiteIds.distinct(), input.difficulty, input.enabled, input.sortOrder.coerceIn(-100_000, 100_000), now, now)
        val before = curricula
        curricula = (curricula + item).toMutableList()
        persistOrRollback { curricula = before }
        return item
    }

    @Synchronized fun updateCurriculum(id: String, input: CurriculumSubmission): Curriculum {
        val index = curricula.indexOfFirst { it.id == id }
        require(index >= 0) { "Curriculum not found" }
        validateText(input.title, "title", 2, 120); validateText(input.domain, "domain", 2, 80)
        require(input.objectives.isNotEmpty() && input.objectives.size <= 30 && input.objectives.all { it.trim().length in 3..300 }) { "Provide 1–30 objectives, each 3–300 characters" }
        require(input.difficulty in DIFFICULTIES) { "difficulty must be one of ${DIFFICULTIES.joinToString()}" }
        require(id !in input.prerequisiteIds && input.prerequisiteIds.size <= 20 && input.prerequisiteIds.all { prerequisite -> curricula.any { it.id == prerequisite } }) { "Prerequisites must reference existing curricula and cannot include this curriculum" }
        val proposedPrerequisites = curricula.associate { item -> item.id to if (item.id == id) input.prerequisiteIds else item.prerequisiteIds }
        fun reachesTarget(currentId: String, targetId: String, visited: MutableSet<String>): Boolean {
            if (currentId == targetId) return true
            if (!visited.add(currentId)) return false
            return proposedPrerequisites[currentId].orEmpty().any { reachesTarget(it, targetId, visited) }
        }
        require(input.prerequisiteIds.none { reachesTarget(it, id, mutableSetOf()) }) { "Prerequisites cannot create a curriculum dependency cycle" }
        val current = curricula[index]
        val updated = current.copy(title = input.title.trim(), domain = input.domain.trim(), objectives = input.objectives.map(String::trim), prerequisiteIds = input.prerequisiteIds.distinct(), difficulty = input.difficulty, enabled = input.enabled, sortOrder = input.sortOrder.coerceIn(-100_000, 100_000), updatedAt = clock())
        val before = curricula
        curricula = curricula.toMutableList().also { it[index] = updated }
        persistOrRollback { curricula = before }
        return updated
    }

    @Synchronized fun addApprovedClaim(input: ClaimSubmission): ApprovedLearningClaim {
        validateText(input.subject, "subject", 1, 180); validateText(input.relation, "relation", 2, 80)
        validateText(input.objectValue, "objectValue", 1, 500); validateText(input.domain, "domain", 2, 80)
        validateText(input.reviewedBy, "reviewedBy", 2, 120)
        require(input.language in LANGUAGES) { "language must be en, sn (Shona), or nd (Ndebele)" }
        require(input.evidenceRefs.isNotEmpty() && input.evidenceRefs.size <= 20) { "At least one evidence reference is required" }
        require(input.evidenceRefs.all { ref ->
            ref.trim().length in 1..2048 && runCatching {
                val uri = java.net.URI(ref.trim())
                when (uri.scheme?.lowercase()) {
                    "https" -> !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)
                    "urn" -> !uri.schemeSpecificPart.isNullOrBlank()
                    else -> false
                }
            }.getOrDefault(false)
        }) { "Evidence references must be HTTPS URLs or URNs" }
        require(input.qualifiers.size <= 20 && input.exceptions.size <= 20) { "Too many qualifiers or exceptions" }
        val normalized = listOf(input.subject, input.relation, input.objectValue, input.domain).joinToString("|").lowercase()
        require(claims.none { listOf(it.subject, it.relation, it.objectValue, it.domain).joinToString("|").lowercase() == normalized && it.status == "APPROVED" }) { "An equivalent approved claim already exists" }
        val item = ApprovedLearningClaim(UUID.randomUUID().toString(), input.subject.trim(), input.relation.trim(), input.objectValue.trim(), input.domain.trim(), input.language, input.scope.trim().take(300), input.qualifiers.map { it.trim().take(300) }, input.exceptions.map { it.trim().take(300) }, input.evidenceRefs.map(String::trim), input.reviewedBy.trim(), clock())
        val before = claims
        claims = (claims + item).toMutableList()
        persistOrRollback { claims = before }
        return item
    }

    @Synchronized fun saveGenerated(items: List<SyntheticQuestion>): List<SyntheticQuestion> {
        val existingKeys = questions.filter { it.status != "DISCARDED" }.map { questionKey(it) }.toMutableSet()
        val inserted = items.filter { existingKeys.add(questionKey(it)) }
        require(questions.size + inserted.size <= MAX_QUESTIONS) { "Synthetic question store is full; review or archive existing examples first" }
        if (inserted.isEmpty()) return emptyList()
        val before = questions
        questions = (questions + inserted).toMutableList()
        persistOrRollback { questions = before }
        return inserted
    }

    private fun questionKey(item: SyntheticQuestion) = listOf(item.curriculumId, item.sourceClaimId, item.questionType, item.requestedLanguage).joinToString("|")

    @Synchronized fun reviewQuestion(id: String, input: QuestionReviewRequest): SyntheticQuestion {
        val index = questions.indexOfFirst { it.id == id }
        require(index >= 0) { "Synthetic question not found" }
        val current = questions[index]
        require(current.status == "CANDIDATE" || current.status == "NEEDS_LANGUAGE_REVIEW") { "Only candidate questions can be reviewed" }
        val action = input.action.uppercase()
        require(action in setOf("ACCEPT", "EDIT", "DISCARD")) { "action must be ACCEPT, EDIT, or DISCARD" }
        val updated = when (action) {
            "ACCEPT" -> {
                require(!current.languageReviewRequired || input.trainerNote.trim().length >= 4) { "Add a note confirming human language review before accepting this translated question" }
                require(input.qualityRating != null && input.qualityRating in 1..5) { "A trainer quality rating from 1 to 5 is required when accepting a question" }
                current.copy(status = "ACCEPTED", languageReviewRequired = false, qualityRating = input.qualityRating, trainerNote = input.trainerNote.trim().take(1000))
            }
            "EDIT" -> {
                val question = input.question?.trim().orEmpty(); val answer = input.expectedAnswer?.trim().orEmpty()
                validateText(question, "question", 5, 700); validateText(answer, "expectedAnswer", 1, 2000)
                current.copy(question = question, expectedAnswer = answer, status = if (current.languageReviewRequired) "NEEDS_LANGUAGE_REVIEW" else "CANDIDATE", trainerNote = input.trainerNote.trim().take(1000))
            }
            else -> current.copy(status = "DISCARDED", trainerNote = input.trainerNote.trim().take(1000))
        }
        val before = questions
        questions = questions.toMutableList().also { it[index] = updated }
        persistOrRollback { questions = before }
        return updated
    }

    private fun load() {
        val target = path ?: return
        if (!Files.exists(target)) return
        try {
            require(Files.size(target) <= MAX_STORE_BYTES) { "Curriculum store exceeds size limit" }
            val root = json.parseToJsonElement(Files.readString(target, StandardCharsets.UTF_8)).jsonObject
            val schema = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
            require(schema == SCHEMA_VERSION) { "Unsupported curriculum schema version $schema" }
            curricula = json.decodeFromJsonElement(ListSerializer(Curriculum.serializer()), root.getValue("curricula")).toMutableList()
            claims = json.decodeFromJsonElement(ListSerializer(ApprovedLearningClaim.serializer()), root.getValue("claims")).toMutableList()
            questions = json.decodeFromJsonElement(ListSerializer(SyntheticQuestion.serializer()), root.getValue("questions")).toMutableList()
            require(curricula.size <= MAX_CURRICULA && claims.size <= MAX_CLAIMS && questions.size <= MAX_QUESTIONS) { "Curriculum store exceeds record limits" }
            require(questions.all { it.synthetic && !it.independentEvidence && it.split == "PRACTICE" }) { "Invalid synthetic/evaluation separation metadata" }
        } catch (failure: Exception) { throw IllegalStateException("Could not load curriculum store; back up and repair it before restarting.", failure) }
    }

    private fun persistOrRollback(rollback: () -> Unit) {
        try { persist() } catch (failure: Exception) { rollback(); throw IllegalStateException("Could not persist curriculum changes; no changes were committed", failure) }
    }
    private fun persist() {
        val target = path ?: return
        val parent = target.toAbsolutePath().parent
        Files.createDirectories(parent)
        val content = """{"schemaVersion":$SCHEMA_VERSION,"curricula":${json.encodeToString(ListSerializer(Curriculum.serializer()), curricula)},"claims":${json.encodeToString(ListSerializer(ApprovedLearningClaim.serializer()), claims)},"questions":${json.encodeToString(ListSerializer(SyntheticQuestion.serializer()), questions)}}"""
        val temporary = Files.createTempFile(parent, target.fileName.toString(), ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
            runCatching { Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun validateText(value: String, field: String, min: Int, max: Int) { require(value.trim().length in min..max) { "$field must be $min–$max characters" } }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val MAX_STORE_BYTES = 32L * 1024 * 1024
        private const val MAX_CURRICULA = 1_000
        private const val MAX_CLAIMS = 100_000
        private const val MAX_QUESTIONS = 100_000
        val LANGUAGES = setOf("en", "sn", "nd")
        val DIFFICULTIES = setOf("FOUNDATION", "RELATIONSHIP", "EXCEPTION", "CAUSAL", "TEMPORAL", "SYNTHESIS")
    }
}
