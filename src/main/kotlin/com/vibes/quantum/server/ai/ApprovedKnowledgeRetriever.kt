package com.vibes.quantum.server.ai

import com.vibes.quantum.server.curriculum.ApprovedLearningClaim
import com.vibes.quantum.server.curriculum.CurriculumStore
import java.util.Locale

/**
 * Small lexical retriever over trainer-approved claims only. Pending research candidates and generated practice
 * questions are deliberately excluded. It returns references for audit; it never fetches or executes source content.
 */
class ApprovedKnowledgeRetriever(private val store: CurriculumStore) {
    fun retrieve(query: String, limit: Int = 8): List<CouncilEvidence> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val queryTerms = terms(query)
        if (queryTerms.isEmpty()) return emptyList()
        return store.allApprovedClaims().mapNotNull { claim ->
            val searchable = listOf(claim.subject, claim.relation, claim.objectValue, claim.domain, claim.scope)
                .plus(claim.qualifiers).plus(claim.exceptions).joinToString(" ")
            val claimTerms = terms(searchable)
            val overlap = queryTerms.intersect(claimTerms)
            if (overlap.isEmpty()) return@mapNotNull null
            val domainBoost = if (queryTerms.any { it in terms(claim.domain) }) 2 else 0
            val subjectBoost = if (queryTerms.any { it in terms(claim.subject) }) 2 else 0
            ScoredClaim(claim, overlap.size + domainBoost + subjectBoost, overlap.size)
        }.filter { it.score > 0 }
            .sortedWith(compareByDescending<ScoredClaim> { it.score }.thenByDescending { it.overlapCount }.thenBy { it.claim.id })
            .take(limit.coerceIn(1, 12))
            .mapIndexed { index, scored -> scored.claim.toEvidence(index + 1) }
    }

    private fun ApprovedLearningClaim.toEvidence(rank: Int): CouncilEvidence {
        val sourceText = buildString {
            append("Trainer-approved local claim: ")
            append(subject); append(' '); append(relation); append(' '); append(objectValue); appendLine(".")
            appendLine("Domain: $domain; language: $language.")
            if (scope.isNotBlank()) appendLine("Scope: ${scope.take(300)}")
            if (qualifiers.isNotEmpty()) appendLine("Qualifiers: ${qualifiers.take(10).joinToString("; ").take(1_500)}")
            if (exceptions.isNotEmpty()) appendLine("Exceptions: ${exceptions.take(10).joinToString("; ").take(1_500)}")
            appendLine("Source references: ${evidenceRefs.take(20).joinToString(", ")}")
            appendLine("Human-reviewed at $reviewedAt; reviewer identity is retained in the local registry and is not sent to model providers.")
            append("This is an approved local claim linked to references, not a substitute for checking the original sources.")
        }.take(6_000)
        val idSuffix = id.replace(Regex("[^A-Za-z0-9._:-]"), "").take(60).ifBlank { rank.toString() }
        return CouncilEvidence(
            id = "local-claim-$idSuffix",
            text = sourceText,
            title = "Approved claim: ${(subject + " " + relation + " " + objectValue).take(160)}",
            url = evidenceRefs.firstOrNull { it.startsWith("https://", true) },
            publisher = "Vibes Quantum trainer-approved claim registry",
            retrievedAt = reviewedAt
        )
    }

    private fun terms(value: String): Set<String> = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .split(Regex("\\s+"))
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .toSet()

    private data class ScoredClaim(val claim: ApprovedLearningClaim, val score: Int, val overlapCount: Int)

    companion object {
        private val STOP_WORDS = setOf(
            "the", "and", "that", "with", "from", "this", "what", "when", "where", "which", "does", "how", "why", "are", "was", "were", "has", "have", "had", "can", "could", "would", "should", "about", "into", "than", "then", "their", "there", "they", "them", "for", "not", "but", "you", "your", "our", "its", "who", "will", "also"
        )
    }
}
