package com.vibes.quantum.server.agents

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Serializable
data class WorkspaceSupervisorConfigRequest(
    val intervalSeconds: Int = 60,
    val maxCycles: Int = 10,
    val failureThreshold: Int = 3,
    val budgetUsd: Double = 2.0,
    val maxSeconds: Int = 3600
)

@Serializable
private data class WorkspaceSupervisorState(
    val configured: Boolean = false,
    val running: Boolean = false,
    val emergencyStop: Boolean = false,
    val intervalSeconds: Int = 60,
    val maxCycles: Int = 10,
    val failureThreshold: Int = 3,
    val budgetUsd: Double = 2.0,
    val maxSeconds: Int = 3600,
    val cycles: Int = 0,
    val failures: Int = 0,
    val spendUsd: Double = 0.0,
    val elapsedSeconds: Long = 0,
    val lastCycleAt: String? = null,
    val lastError: String? = null,
    val updatedAt: String = Instant.now().toString()
)

/** Persistent, fail-closed controls for continuous workspace jobs. */
class WorkspaceControlService {
    companion object {
        private val lock = Any()
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        private fun root(): Path {
            val configured = System.getProperty("vibes.workspace.control.dir")
                ?.takeIf { it.isNotBlank() }
            return (if (configured != null) Paths.get(configured) else
                Paths.get(System.getProperty("user.home"), ".vibes-quantum", "workspace-control"))
                .toAbsolutePath().normalize()
        }

        private fun stateFile(): Path = root().resolve("supervisor-state.json")

        private fun load(): WorkspaceSupervisorState {
            val file = stateFile()
            if (!Files.exists(file)) return WorkspaceSupervisorState()
            return try {
                json.decodeFromString<WorkspaceSupervisorState>(Files.readString(file))
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "Supervisor ledger is unreadable; continuous work is blocked.", failure
                )
            }
        }

        private fun save(state: WorkspaceSupervisorState) {
            val directory = root()
            Files.createDirectories(directory)
            val destination = stateFile()
            val temporary = directory.resolve(".supervisor-${UUID.randomUUID()}.tmp")
            try {
                Files.writeString(temporary, json.encodeToString(
                    state.copy(updatedAt = Instant.now().toString())
                ))
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        private fun canRun(s: WorkspaceSupervisorState): Boolean =
            s.configured && s.running && !s.emergencyStop &&
                s.cycles < s.maxCycles && s.failures < s.failureThreshold &&
                s.spendUsd < s.budgetUsd && s.elapsedSeconds < s.maxSeconds.toLong()

        private fun asJson(s: WorkspaceSupervisorState): JsonObject = buildJsonObject {
            put("configured", s.configured)
            put("running", s.running)
            put("emergencyStop", s.emergencyStop)
            put("canRun", canRun(s))
            put("intervalSeconds", s.intervalSeconds)
            put("maxCycles", s.maxCycles)
            put("failureThreshold", s.failureThreshold)
            put("budgetUsd", s.budgetUsd)
            put("maxSeconds", s.maxSeconds)
            put("cycles", s.cycles)
            put("failures", s.failures)
            put("spendUsd", s.spendUsd)
            put("elapsedSeconds", s.elapsedSeconds)
            put("lastCycleAt", s.lastCycleAt?.let { JsonPrimitive(it) } ?: JsonPrimitive(""))
            put("lastError", s.lastError?.let { JsonPrimitive(it) } ?: JsonPrimitive(""))
            put("updatedAt", s.updatedAt)
        }
    }

    fun status(): JsonObject = synchronized(lock) { asJson(load()) }

    fun configure(
        intervalSeconds: Int, maxCycles: Int, failureThreshold: Int,
        budgetUsd: Double, maxSeconds: Int
    ): JsonObject = synchronized(lock) {
        require(intervalSeconds in 1..86400) { "intervalSeconds must be between 1 and 86400." }
        require(maxCycles in 1..100000) { "maxCycles must be between 1 and 100000." }
        require(failureThreshold in 1..1000) { "failureThreshold must be between 1 and 1000." }
        require(budgetUsd.isFinite() && budgetUsd > 0.0) {
            "budgetUsd must be finite and greater than zero."
        }
        require(maxSeconds in 1..604800) { "maxSeconds must be between 1 and 604800." }
        val updated = load().copy(
            configured = true, running = false,
            intervalSeconds = intervalSeconds, maxCycles = maxCycles,
            failureThreshold = failureThreshold, budgetUsd = budgetUsd,
            maxSeconds = maxSeconds, cycles = 0, failures = 0,
            spendUsd = 0.0, elapsedSeconds = 0, lastCycleAt = null,
            lastError = null, updatedAt = Instant.now().toString()
        )
        save(updated)
        asJson(updated)
    }

    fun start(): JsonObject = synchronized(lock) {
        val old = load()
        check(old.configured) { "Configure supervisor limits before starting it." }
        val updated = old.copy(
            running = !old.emergencyStop && old.cycles < old.maxCycles &&
                old.failures < old.failureThreshold && old.spendUsd < old.budgetUsd &&
                old.elapsedSeconds < old.maxSeconds.toLong(),
            updatedAt = Instant.now().toString()
        )
        save(updated)
        asJson(updated)
    }

    fun stop(): JsonObject = synchronized(lock) {
        val updated = load().copy(running = false, updatedAt = Instant.now().toString())
        save(updated); asJson(updated)
    }

    fun emergencyStop(): JsonObject = synchronized(lock) {
        val updated = load().copy(
            running = false, emergencyStop = true,
            lastError = "Emergency stop activated by an administrator.",
            updatedAt = Instant.now().toString()
        )
        save(updated); asJson(updated)
    }

    fun clearEmergencyStop(): JsonObject = synchronized(lock) {
        val updated = load().copy(
            running = false, emergencyStop = false, lastError = null,
            updatedAt = Instant.now().toString()
        )
        save(updated); asJson(updated)
    }

    fun recordCycle(success: Boolean, spendUsd: Double, durationSeconds: Long): JsonObject =
        synchronized(lock) {
            require(spendUsd.isFinite() && spendUsd >= 0.0) {
                "Cycle spend must be finite and non-negative."
            }
            require(durationSeconds >= 0L) { "Cycle duration cannot be negative." }
            val old = load()
            check(old.configured && old.running && !old.emergencyStop) {
                "Supervisor is not running; cycle result was not accepted."
            }
            var updated = old.copy(
                cycles = old.cycles + 1,
                failures = if (success) 0 else old.failures + 1,
                spendUsd = old.spendUsd + spendUsd,
                elapsedSeconds = old.elapsedSeconds + durationSeconds,
                lastCycleAt = Instant.now().toString(),
                lastError = if (success) null else "The latest cycle did not pass.",
                updatedAt = Instant.now().toString()
            )
            if (updated.cycles >= updated.maxCycles ||
                updated.failures >= updated.failureThreshold ||
                updated.spendUsd >= updated.budgetUsd ||
                updated.elapsedSeconds >= updated.maxSeconds.toLong()) {
                updated = updated.copy(running = false)
            }
            save(updated)
            asJson(updated)
        }

    fun snapshot(
        sourcePath: Path, versionsDir: Path, label: String, knownGood: Boolean = false
    ): JsonObject {
        val source = sourcePath.toAbsolutePath().normalize()
        val versions = versionsDir.toAbsolutePath().normalize()
        require(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            "Snapshot source is not an existing directory: $source"
        }
        require(!versions.startsWith(source) && !source.startsWith(versions)) {
            "Snapshot source and destination must be separate directory trees."
        }
        val safeLabel = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
            .ifBlank { "snapshot" }
        val snapshotDir = versions.resolve(
            "$safeLabel-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        ).normalize()
        require(snapshotDir.startsWith(versions)) { "Snapshot destination escaped versions directory." }

        return synchronized(lock) {
            Files.createDirectories(versions)
            val projectCopy = snapshotDir.resolve("project")
            try {
                Files.createDirectories(projectCopy)
                Files.walk(source).use { paths ->
                    paths.sorted().forEach { item ->
                        if (Files.isSymbolicLink(item)) {
                            throw IllegalArgumentException("Symbolic links are not allowed in snapshots: $item")
                        }
                        val destination = projectCopy.resolve(source.relativize(item)).normalize()
                        require(destination.startsWith(projectCopy)) { "Snapshot path escaped destination." }
                        when {
                            Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(destination)
                            Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS) -> {
                                Files.createDirectories(destination.parent)
                                Files.copy(item, destination)
                            }
                        }
                    }
                }
                val hashes = treeHashes(projectCopy)
                val manifest = buildJsonObject {
                    put("label", safeLabel)
                    put("createdAt", Instant.now().toString())
                    put("knownGood", knownGood)
                    put("fileCount", hashes.size)
                    put("sha256", JsonObject(hashes.mapValues { JsonPrimitive(it.value) }))
                }
                Files.writeString(snapshotDir.resolve("manifest.json"), json.encodeToString(manifest))
                buildJsonObject {
                    put("label", safeLabel)
                    put("directory", snapshotDir.toString())
                    put("knownGood", knownGood)
                    put("fileCount", hashes.size)
                    put("createdAt", Instant.now().toString())
                }
            } catch (failure: Exception) {
                runCatching { snapshotDir.toFile().deleteRecursively() }
                throw failure
            }
        }
    }

    fun compare(baselinePath: Path, candidatePath: Path): JsonObject {
        val baseline = baselinePath.toAbsolutePath().normalize()
        val candidate = candidatePath.toAbsolutePath().normalize()
        require(Files.isDirectory(baseline)) { "Baseline directory is missing: $baseline" }
        require(Files.isDirectory(candidate)) { "Candidate directory is missing: $candidate" }
        val before = treeHashes(baseline)
        val after = treeHashes(candidate)
        val added = (after.keys - before.keys).sorted()
        val removed = (before.keys - after.keys).sorted()
        val changed = (before.keys intersect after.keys).filter { before[it] != after[it] }.sorted()
        val unchanged = (before.keys intersect after.keys).count { before[it] == after[it] }
        return buildJsonObject {
            put("counts", buildJsonObject {
                put("added", added.size); put("removed", removed.size); put("changed", changed.size)
                put("unchanged", unchanged); put("baselineFiles", before.size); put("candidateFiles", after.size)
            })
            put("added", jsonArrayOfStrings(added))
            put("removed", jsonArrayOfStrings(removed))
            put("changed", jsonArrayOfStrings(changed))
        }
    }

    private fun treeHashes(root: Path): Map<String, String> {
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Not a directory: $root" }
        val result = sortedMapOf<String, String>()
        Files.walk(root).use { paths ->
            paths.forEach { item ->
                if (Files.isSymbolicLink(item)) {
                    throw IllegalArgumentException("Symbolic links are not allowed: $item")
                }
                if (Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)) {
                    val relative = root.relativize(item).toString().replace('\\', '/')
                    val digest = MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(item)).joinToString("") { "%02x".format(it) }
                    result[relative] = digest
                }
            }
        }
        return result
    }

    private fun jsonArrayOfStrings(values: List<String>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
}
