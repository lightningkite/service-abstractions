package com.lightningkite.services.ai.ollama

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Manages an Ollama server process and model availability.
 *
 * Optional helper that can auto-start `ollama serve` and auto-pull models. Use when
 * local developer ergonomics matter; skip in production where Ollama is managed
 * out-of-band.
 *
 * Opt in via the `autoStart=true` / `autoPull=true` URL parameters on the `ollama://`
 * scheme; omitted by default.
 */
public class OllamaManager(
    public val baseUrl: String = "http://localhost:11434",
) {
    private val logger = KotlinLogging.logger {}

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = Long.MAX_VALUE
        }
        engine {
            requestTimeout = 0 // Long pulls
        }
    }

    private var serverProcess: Process? = null
    private var shutdownHookRegistered = false

    public fun isInstalled(): Boolean = findOllamaExecutable() != null

    public fun findOllamaExecutable(): String? {
        val os = System.getProperty("os.name").lowercase()

        val paths = when {
            os.contains("mac") -> listOf(
                "/usr/local/bin/ollama",
                "/opt/homebrew/bin/ollama",
                "${System.getProperty("user.home")}/.ollama/bin/ollama",
            )
            os.contains("linux") -> listOf(
                "/usr/bin/ollama",
                "/usr/local/bin/ollama",
                "${System.getProperty("user.home")}/.local/bin/ollama",
            )
            os.contains("windows") -> listOf(
                "${System.getenv("LOCALAPPDATA")}\\Ollama\\ollama.exe",
                "${System.getenv("ProgramFiles")}\\Ollama\\ollama.exe",
            )
            else -> emptyList()
        }

        for (path in paths) {
            if (File(path).exists() && File(path).canExecute()) return path
        }

        return try {
            val command = if (os.contains("windows")) listOf("where", "ollama")
            else listOf("which", "ollama")

            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val result = process.inputStream.bufferedReader().readLine()
            val exitCode = process.waitFor()

            if (exitCode == 0 && result != null && File(result).exists()) result else null
        } catch (e: Exception) {
            logger.debug { "Failed to locate ollama via PATH: ${e.message}" }
            null
        }
    }

    public suspend fun isServerRunning(): Boolean = try {
        httpClient.get("$baseUrl/api/tags").status.isSuccess()
    } catch (e: Exception) {
        false
    }

    public suspend fun startServer(registerShutdownHook: Boolean = true): Process? {
        if (isServerRunning()) {
            logger.info { "Ollama server already running at $baseUrl" }
            return null
        }

        val executable = findOllamaExecutable()
            ?: throw IllegalStateException(
                "Ollama is not installed. Install from https://ollama.ai/download",
            )

        logger.info { "Starting Ollama server using $executable" }

        val processBuilder = ProcessBuilder(executable, "serve").redirectErrorStream(true)

        if (baseUrl != "http://localhost:11434") {
            val host = baseUrl.removePrefix("http://").removePrefix("https://")
            processBuilder.environment()["OLLAMA_HOST"] = host
        }

        serverProcess = processBuilder.start()

        if (registerShutdownHook && !shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(Thread { stopServerSync() })
            shutdownHookRegistered = true
        }

        waitForServerReady()
        logger.info { "Ollama server started successfully" }
        return serverProcess
    }

    public suspend fun waitForServerReady(
        timeout: Duration = 30.seconds,
        pollInterval: Duration = 500.milliseconds,
    ) {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (isServerRunning()) return
            serverProcess?.let { p ->
                if (!p.isAlive) {
                    val output = p.inputStream.bufferedReader().readText()
                    throw IllegalStateException("Ollama server process died. Output: $output")
                }
            }
            delay(pollInterval)
        }
        throw IllegalStateException(
            "Ollama server failed to start within $timeout. " +
                "Check if port ${baseUrl.substringAfterLast(":")} is available.",
        )
    }

    public suspend fun stopServer(): Unit = withContext(Dispatchers.IO) { stopServerSync() }

    private fun stopServerSync() {
        serverProcess?.let { process ->
            if (process.isAlive) {
                logger.info { "Stopping Ollama server" }
                process.destroy()
                try {
                    process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (process.isAlive) process.destroyForcibly()
            }
            serverProcess = null
        }
    }

    public suspend fun listModels(): List<OllamaTagEntryPublic> {
        val response = httpClient.get("$baseUrl/api/tags")
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to list models: ${response.status}")
        }
        val tags: OllamaTagsResponsePublic = response.body()
        return tags.models
    }

    public suspend fun isModelPulled(model: String): Boolean {
        val models = listModels()
        val normalized = normalizeModelName(model)
        return models.any {
            normalizeModelName(it.name) == normalized ||
                it.name == model ||
                it.name.startsWith("$model:")
        }
    }

    public suspend fun pullModel(
        model: String,
        onProgress: (OllamaPullProgress) -> Unit = {},
    ) {
        logger.info { "Pulling model: $model" }

        httpClient.preparePost("$baseUrl/api/pull") {
            contentType(ContentType.Application.Json)
            setBody(OllamaPullRequest(name = model))
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Failed to start pull: ${response.status}")
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (line.isBlank()) continue
                try {
                    val progress = json.decodeFromString(OllamaPullProgress.serializer(), line)
                    onProgress(progress)
                    if (progress.error != null) {
                        throw IllegalStateException("Pull failed: ${progress.error}")
                    }
                } catch (e: IllegalStateException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug { "Failed to parse progress: $line" }
                }
            }
        }

        logger.info { "Successfully pulled model: $model" }
    }

    public suspend fun ensureReady(
        model: String,
        startServer: Boolean = true,
        pullModel: Boolean = true,
        onProgress: (OllamaPullProgress) -> Unit = { progress ->
            progress.status?.let { logger.info { "Ollama: $it" } }
        },
    ) {
        if (startServer && !isServerRunning()) {
            if (!isInstalled()) {
                throw IllegalStateException(
                    "Ollama is not installed. Install from https://ollama.ai/download",
                )
            }
            startServer()
        }
        if (!isServerRunning()) {
            throw IllegalStateException(
                "Ollama server is not running at $baseUrl. Start with 'ollama serve' or set autoStart=true.",
            )
        }
        if (pullModel && !isModelPulled(model)) pullModel(model, onProgress)
        if (!isModelPulled(model)) {
            throw IllegalStateException(
                "Model '$model' is not available. Pull with 'ollama pull $model' or set autoPull=true.",
            )
        }
        logger.info { "Ollama ready with model: $model" }
    }

    private fun normalizeModelName(name: String): String {
        val baseName = name.substringBefore(":")
        val tag = name.substringAfter(":", "latest")
        return if (tag == "latest") baseName else "$baseName:$tag"
    }

    public fun close(): Unit = httpClient.close()
}

// Public DTOs for the manager — these are distinct from the private wire DTOs used by
// the OllamaLlmAccess service to keep the public API stable even as the wire format evolves.

@Serializable
public data class OllamaTagsResponsePublic(
    val models: List<OllamaTagEntryPublic> = emptyList(),
)

@Serializable
public data class OllamaTagEntryPublic(
    val name: String,
    val modified_at: String? = null,
    val size: Long? = null,
    val digest: String? = null,
)

@Serializable
internal data class OllamaPullRequest(
    val name: String,
    val insecure: Boolean = false,
    val stream: Boolean = true,
)

@Serializable
public data class OllamaPullProgress(
    val status: String? = null,
    val digest: String? = null,
    val total: Long? = null,
    val completed: Long? = null,
    val error: String? = null,
) {
    public val progressPercent: Double?
        get() = if (total != null && total > 0 && completed != null) {
            (completed.toDouble() / total.toDouble()) * 100
        } else null
}
