package com.vibes.quantum.server.agents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A sandbox is fail-closed. No shell command or code from a task is ever run directly on the host. */
data class SandboxResult(val success: Boolean, val output: String, val warningCode: String? = null)

interface SandboxExecutor {
    val available: Boolean
    suspend fun execute(language: String, source: String, timeoutMillis: Long): SandboxResult
}

class DisabledSandboxExecutor : SandboxExecutor {
    override val available: Boolean = false
    override suspend fun execute(language: String, source: String, timeoutMillis: Long) =
        SandboxResult(false, "Code execution is disabled because no isolated sandbox image is configured.", "SANDBOX_NOT_CONFIGURED")
}

/**
 * Executes only fixed runtime entrypoints inside Docker/Podman-compatible containers. The image is administrator-configured,
 * must already be present locally, and is never pulled automatically. No network, secrets, host directories, or elevated
 * privileges are passed to the container. If the runtime is unavailable, execution is denied.
 */
class ContainerSandboxExecutor(
    private val image: String,
    private val runtime: String = System.getenv("VIBES_AGENT_SANDBOX_RUNTIME")?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,64}")) } ?: "docker"
) : SandboxExecutor {
    override val available: Boolean = image.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,255}")) && !image.startsWith("-")

    override suspend fun execute(language: String, source: String, timeoutMillis: Long): SandboxResult = withContext(Dispatchers.IO) {
        if (!available) return@withContext SandboxResult(false, "Configured sandbox image name is invalid.", "SANDBOX_INVALID_CONFIG")
        if (source.isBlank() || source.length > 100_000) return@withContext SandboxResult(false, "Code must contain 1–100,000 characters.", "SANDBOX_INVALID_INPUT")
        val fixedCommand: List<String>
        val fileName: String
        when (language.lowercase()) {
            "python", "python3" -> { fixedCommand = listOf("python", "-I", "-S", "/workspace/main.py"); fileName = "main.py" }
            "javascript", "js", "node" -> { fixedCommand = listOf("node", "--disable-proto=throw", "/workspace/main.js"); fileName = "main.js" }
            else -> return@withContext SandboxResult(false, "This sandbox currently supports Python and JavaScript runtime tasks only. Other build languages fail closed.", "SANDBOX_LANGUAGE_UNSUPPORTED")
        }
        val directory = Files.createTempDirectory("vibes-agent-sandbox-")
        val containerName = "vq-agent-${UUID.randomUUID().toString().replace("-", "").take(18)}"
        var process: Process? = null
        try {
            val sourcePath = directory.resolve(fileName)
            Files.writeString(sourcePath, source, StandardCharsets.UTF_8)
            // Restrict the host-side temporary directory and the supplied program to read-only for the container.
            runCatching { Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x")) }
            runCatching { Files.setPosixFilePermissions(sourcePath, java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--")) }
            val args = listOf(
                runtime, "run", "--rm", "--pull=never", "--name", containerName,
                "--network", "none", "--read-only", "--memory=192m", "--cpus=0.5", "--pids-limit=32",
                "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user=65534:65534",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=8m",
                "--mount", "type=bind,src=${directory.toAbsolutePath()},dst=/workspace,readonly",
                "--workdir", "/workspace", image
            ) + fixedCommand
            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            process = proc
            val captured = StringBuilder()
            val reader = Thread {
                runCatching {
                    proc.inputStream.use { input ->
                        val buf = ByteArray(1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            synchronized(captured) {
                                val remaining = 8_192 - captured.length
                                if (remaining > 0) captured.append(String(buf, 0, minOf(n, remaining), StandardCharsets.UTF_8))
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val completed = runInterruptible { proc.waitFor(timeoutMillis.coerceIn(500, 15_000), TimeUnit.MILLISECONDS) }
            if (!completed) {
                proc.destroyForcibly()
                // Best-effort container cleanup; generated identifier is safe and is not user-controlled.
                runCatching { ProcessBuilder(runtime, "kill", containerName).start().waitFor(1, TimeUnit.SECONDS) }
                runCatching { ProcessBuilder(runtime, "rm", "-f", containerName).start().waitFor(1, TimeUnit.SECONDS) }
                reader.join(500)
                return@withContext SandboxResult(false, synchronized(captured) { captured.toString() } + "\nExecution timed out and was stopped.", "SANDBOX_TIMEOUT")
            }
            reader.join(1_000)
            val output = synchronized(captured) { captured.toString() }.take(8_192)
            if (proc.exitValue() == 0) SandboxResult(true, output.ifBlank { "Execution completed with no output." })
            else SandboxResult(false, output.ifBlank { "Sandbox process returned a non-zero exit status." }, "SANDBOX_EXIT_NONZERO")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            runCatching { process?.destroyForcibly() }
            runCatching { ProcessBuilder(runtime, "kill", containerName).start().waitFor(1, TimeUnit.SECONDS) }
            runCatching { ProcessBuilder(runtime, "rm", "-f", containerName).start().waitFor(1, TimeUnit.SECONDS) }
            throw cancelled
        } catch (_: Exception) {
            runCatching { process?.destroyForcibly() }
            runCatching { ProcessBuilder(runtime, "kill", containerName).start().waitFor(1, TimeUnit.SECONDS) }
            runCatching { ProcessBuilder(runtime, "rm", "-f", containerName).start().waitFor(1, TimeUnit.SECONDS) }
            SandboxResult(false, "Sandbox runtime failed; code was not run on the host.", "SANDBOX_RUNTIME_FAILURE")
        } finally {
            runCatching { Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
            runCatching { directory.toFile().deleteRecursively() }
        }
    }
}
