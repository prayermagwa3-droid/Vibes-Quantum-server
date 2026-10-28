package com.vibes.quantum.server.memory

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UnifiedMemoryIndexTest {
    private val testKey = "a".repeat(64)
    private fun index() = UnifiedMemoryIndex(Files.createTempDirectory("vq-memory-test").resolve("memory.enc"), testKey)

    @Test fun memoryIsIsolatedByPseudonymousScope() {
        val memory = index()
        memory.upsert(UnifiedMemoryUpsertRequest("user:scope-0001", "Prefers Kotlin examples", kind = "PREFERENCE"))
        memory.upsert(UnifiedMemoryUpsertRequest("user:scope-0002", "Prefers Python examples", kind = "PREFERENCE"))

        assertEquals("Prefers Kotlin examples", memory.search("user:scope-0001", "Kotlin").single().memory.content)
        assertTrue(memory.search("user:scope-0001", "Python").isEmpty())
        assertEquals(2, memory.stats()["records"])
    }

    @Test fun normalizedDuplicateUpdatesOneRecordAndPreservesProvenanceFields() {
        val memory = index()
        val first = memory.upsert(UnifiedMemoryUpsertRequest(
            "app:subject-001", "The deployment uses Gradle", kind = "DECISION", tags = listOf("build"),
            sourceType = "PROJECT_NOTE", sourceReference = "commit:abc123"
        ))
        val second = memory.upsert(UnifiedMemoryUpsertRequest(
            "app:subject-001", " the   deployment USES gradle ", kind = "DECISION", tags = listOf("release"),
            userReviewed = true
        ))

        assertEquals(first.id, second.id)
        assertEquals(1, memory.stats()["records"])
        assertEquals(setOf("build", "release"), second.tags.toSet())
        assertEquals("commit:abc123", second.sourceReference)
        assertEquals("USER_REVIEWED", second.reviewStatus)
        assertEquals(2, second.version)
    }

    @Test fun indexSurvivesReopenAndSearchUsageIsRecorded() {
        val file = Files.createTempDirectory("vq-memory-durable").resolve("memory.json")
        val firstIndex = UnifiedMemoryIndex(file, testKey)
        val record = firstIndex.upsert(UnifiedMemoryUpsertRequest(
            "app:subject-002", "Release reports must include test evidence", kind = "PROJECT_DECISION"
        ))

        val reopened = UnifiedMemoryIndex(file, testKey)
        val hit = reopened.search("app:subject-002", "release test evidence").single()
        assertEquals(record.id, hit.memory.id)
        assertEquals(1, hit.memory.accessCount)
        assertNotNull(hit.memory.lastAccessedAt)
        assertTrue(hit.score > 0.0)
    }

    @Test fun expiredMemoryIsHiddenFromSearchAndDefaultList() {
        val memory = index()
        memory.upsert(UnifiedMemoryUpsertRequest(
            "app:subject-003", "Yesterday weather forecast", kind = "FACT",
            expiresAt = Instant.parse("2000-01-01T00:00:00Z").toString()
        ))
        assertTrue(memory.search("app:subject-003", "weather forecast").isEmpty())
        assertTrue(memory.list("app:subject-003").isEmpty())
        assertEquals(1, memory.list("app:subject-003", includeExpired = true).size)
    }

    @Test fun credentialLikeMemoryIsRejected() {
        val memory = index()
        assertFailsWith<IllegalArgumentException> {
            memory.upsert(UnifiedMemoryUpsertRequest(
                "app:subject-004", "api_key='123456789012345678901234567890'", kind = "NOTE"
            ))
        }
        assertEquals(0, memory.stats()["records"])
    }

    @Test fun deletionIsScopeBoundAndScopeDeletionIsPermanent() {
        val memory = index()
        val record = memory.upsert(UnifiedMemoryUpsertRequest("app:subject-005", "Project needs a benchmark suite"))
        memory.upsert(UnifiedMemoryUpsertRequest("app:subject-006", "Separate user's project decision"))

        assertFalse(memory.forget("app:subject-006", record.id))
        assertEquals(2, memory.stats()["records"])
        assertTrue(memory.forget("app:subject-005", record.id))
        assertEquals(1, memory.forgetScope("app:subject-006"))
        assertEquals(0, memory.stats()["records"])
    }

    @Test fun invalidScopeAndLimitsAreRejected() {
        val memory = index()
        assertFailsWith<IllegalArgumentException> { memory.list("raw-user-id") }
        assertFailsWith<IllegalArgumentException> { memory.search("app:subject-007", "thing", 0) }
        assertFailsWith<IllegalArgumentException> {
            memory.upsert(UnifiedMemoryUpsertRequest("app:subject-007", "Valid content", confidence = 1.5))
        }
    }

    @Test fun corruptPersistedIndexFailsClosedInsteadOfReplacingData() {
        val file = Files.createTempDirectory("vq-memory-corrupt").resolve("memory.json")
        val original = "not valid json"
        Files.writeString(file, original)
        assertFailsWith<IllegalStateException> { UnifiedMemoryIndex(file, testKey) }
        assertEquals(original, Files.readString(file))
    }

    @Test fun persistedMemoryIsEncryptedAtRest() {
        val file = Files.createTempDirectory("vq-memory-encrypted").resolve("memory.enc")
        val index = UnifiedMemoryIndex(file, testKey)
        index.upsert(UnifiedMemoryUpsertRequest("app:subject-009", "Sensitive project decision text"))
        val payload = Files.readString(file)
        assertFalse(payload.contains("Sensitive project decision text"))
        assertTrue(payload.contains("ciphertext"))
        assertTrue(UnifiedMemoryIndex(file, testKey).configured())
    }

    @Test fun indexIsDisabledWithoutAnEncryptionKey() {
        val file = Files.createTempDirectory("vq-memory-disabled").resolve("memory.enc")
        val index = UnifiedMemoryIndex(file, null)
        assertFalse(index.configured())
        assertFailsWith<IllegalStateException> {
            index.upsert(UnifiedMemoryUpsertRequest("app:subject-010", "Should not be persisted"))
        }
        assertFalse(Files.exists(file))
    }

    @Test fun semanticHashDoesNotDependOnCaseOrWhitespace() {
        val memory = index()
        val first = memory.upsert(UnifiedMemoryUpsertRequest("app:subject-008", "Cache should be bounded", kind = "DECISION"))
        val duplicate = memory.upsert(UnifiedMemoryUpsertRequest("app:subject-008", " CACHE   SHOULD be bounded ", kind = "DECISION"))
        assertEquals(first.contentHash, duplicate.contentHash)
        assertNotEquals(first.createdAt, "")
    }
}
