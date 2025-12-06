package com.lightningkite.services.ai.koog

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Manages Ollama server lifecycle and model availability.
 *
 * This class provides functionality to:
 * - Detect if Ollama is installed on the system
 * - Start and stop the Ollama server process
 * - Check if specific models are available locally
 * - Pull (download) models from the Ollama registry
 *
 * ## Usage
 *
 * ```kotlin
 * val manager = OllamaManager()
 *
 * // Ensure everything is ready for a specific model
 * manager.ensureReady("llama3.2") { progress ->
 *     println("Pulling model: $progress")
 * }
 *
 * // Now use the model via Koog or LangChain4J
 * ```
 *
 * ## Platform Support
 *
 * Supports macOS, Linux, and Windows with automatic detection of Ollama installation paths.
 *
 * @property baseUrl The Ollama server URL (default: http://localhost:11434)
 */
public class OllamaManager(
    public val baseUrl: String = "http://localhost:11434"
) {
    private val logger = KotlinLogging.logger {}

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        engine {
            requestTimeout = 0 // No timeout for long-running pulls
        }
    }

    private var serverProcess: Process? = null
    private var shutdownHookRegistered = false

    /**
     * Checks if Ollama is installed on the system.
     *
     * Searches common installation paths and the system PATH.
     *
     * @return true if Ollama executable is found
     */
    public fun isInstalled(): Boolean = findOllamaExecutable() != null

    /**
     * Returns the path to the Ollama executable, or null if not found.
     */
    public fun findOllamaExecutable(): String? {
        val os = System.getProperty("os.name").lowercase()

        // Platform-specific paths to check
        val paths = when {
            os.contains("mac") -> listOf(
                "/usr/local/bin/ollama",
                "/opt/homebrew/bin/ollama",
                "${System.getProperty("user.home")}/.ollama/bin/ollama"
            )
            os.contains("linux") -> listOf(
                "/usr/bin/ollama",
                "/usr/local/bin/ollama",
                "${System.getProperty("user.home")}/.local/bin/ollama"
            )
            os.contains("windows") -> listOf(
                "${System.getenv("LOCALAPPDATA")}\\Ollama\\ollama.exe",
                "${System.getenv("ProgramFiles")}\\Ollama\\ollama.exe"
            )
            else -> emptyList()
        }

        // Check explicit paths first
        for (path in paths) {
            if (File(path).exists() && File(path).canExecute()) {
                return path
            }
        }

        // Fall back to PATH lookup using 'which' or 'where'
        return try {
            val command = if (os.contains("windows")) {
                listOf("where", "ollama")
            } else {
                listOf("which", "ollama")
            }

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val result = process.inputStream.bufferedReader().readLine()
            val exitCode = process.waitFor()

            if (exitCode == 0 && result != null && File(result).exists()) {
                result
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug { "Failed to locate ollama via PATH: ${e.message}" }
            null
        }
    }

    /**
     * Checks if the Ollama server is currently running and responding.
     *
     * @return true if the server responds to API requests
     */
    public suspend fun isServerRunning(): Boolean {
        return try {
            val response = httpClient.get("$baseUrl/api/tags")
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Starts the Ollama server in the background.
     *
     * If the server is already running, this method does nothing.
     * The server process will be automatically stopped when the JVM exits
     * if [registerShutdownHook] is true (default).
     *
     * @param registerShutdownHook If true, registers a JVM shutdown hook to stop the server
     * @return The started Process, or null if the server was already running
     * @throws IllegalStateException if Ollama is not installed
     */
    public suspend fun startServer(registerShutdownHook: Boolean = true): Process? {
        if (isServerRunning()) {
            logger.info { "Ollama server already running at $baseUrl" }
            return null
        }

        val executable = findOllamaExecutable()
            ?: throw IllegalStateException(
                "Ollama is not installed. Please install it from https://ollama.ai/download"
            )

        logger.info { "Starting Ollama server using $executable" }

        val processBuilder = ProcessBuilder(executable, "serve")
            .redirectErrorStream(true)

        // Set OLLAMA_HOST if using non-default port
        if (baseUrl != "http://localhost:11434") {
            val host = baseUrl.removePrefix("http://").removePrefix("https://")
            processBuilder.environment()["OLLAMA_HOST"] = host
        }

        serverProcess = processBuilder.start()

        // Register shutdown hook to clean up
        if (registerShutdownHook && !shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(Thread {
                stopServerSync()
            })
            shutdownHookRegistered = true
        }

        // Wait for server to be ready
        waitForServerReady()

        logger.info { "Ollama server started successfully" }
        return serverProcess
    }

    /**
     * Waits for the Ollama server to become responsive.
     *
     * @param timeout Maximum time to wait
     * @param pollInterval Time between connection attempts
     * @throws IllegalStateException if server doesn't start within timeout
     */
    public suspend fun waitForServerReady(
        timeout: Duration = 30.seconds,
        pollInterval: Duration = 500.milliseconds
    ) {
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeout.inWholeMilliseconds

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isServerRunning()) {
                return
            }

            // Check if process died
            serverProcess?.let { process ->
                if (!process.isAlive) {
                    val output = process.inputStream.bufferedReader().readText()
                    throw IllegalStateException(
                        "Ollama server process died unexpectedly. Output: $output"
                    )
                }
            }

            delay(pollInterval)
        }

        throw IllegalStateException(
            "Ollama server failed to start within $timeout. " +
                "Check if port ${baseUrl.substringAfterLast(":")} is available."
        )
    }

    /**
     * Stops the Ollama server if it was started by this manager.
     *
     * Does nothing if the server wasn't started by this manager or is already stopped.
     */
    public suspend fun stopServer() {
        withContext(Dispatchers.IO) {
            stopServerSync()
        }
    }

    private fun stopServerSync() {
        serverProcess?.let { process ->
            if (process.isAlive) {
                logger.info { "Stopping Ollama server" }
                process.destroy()

                // Give it a moment to shut down gracefully
                try {
                    process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                // Force kill if still running
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
            serverProcess = null
        }
    }

    /**
     * Lists all models currently available locally.
     *
     * @return List of model information
     */
    public suspend fun listModels(): List<OllamaModelInfo> {
        val response = httpClient.get("$baseUrl/api/tags")

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to list models: ${response.status}")
        }

        val tagsResponse = response.body<OllamaTagsResponse>()
        return tagsResponse.models
    }

    /**
     * Checks if a specific model is available locally.
     *
     * @param model Model name (e.g., "llama3.2", "llama3.2:latest", "llama3.2:70b")
     * @return true if the model is downloaded
     */
    public suspend fun isModelPulled(model: String): Boolean {
        val models = listModels()
        val normalizedModel = normalizeModelName(model)

        return models.any { modelInfo ->
            normalizeModelName(modelInfo.name) == normalizedModel ||
                modelInfo.name == model ||
                modelInfo.name.startsWith("$model:")
        }
    }

    /**
     * Pulls (downloads) a model from the Ollama registry.
     *
     * This operation may take a long time for large models.
     * Progress updates are provided via the callback.
     *
     * @param model Model name to pull
     * @param onProgress Callback for progress updates
     * @throws IllegalStateException if pull fails
     */
    public suspend fun pullModel(
        model: String,
        onProgress: (OllamaPullProgress) -> Unit = {}
    ) {
        logger.info { "Pulling model: $model" }

        httpClient.preparePost("$baseUrl/api/pull") {
            contentType(ContentType.Application.Json)
            setBody(OllamaPullRequest(name = model))
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Failed to start pull: ${response.status}")
            }

            // Stream the response as NDJSON (newline-delimited JSON)
            // Use buffered reader to read line by line
            val reader = response.bodyAsChannel().toInputStream().bufferedReader()

            reader.useLines { lines ->
                for (line in lines) {
                    if (line.isNotBlank()) {
                        try {
                            val progress = json.decodeFromString<OllamaPullProgress>(line)
                            onProgress(progress)

                            if (progress.error != null) {
                                throw IllegalStateException("Pull failed: ${progress.error}")
                            }
                        } catch (e: Exception) {
                            if (e is IllegalStateException) throw e
                            logger.debug { "Failed to parse progress: $line" }
                        }
                    }
                }
            }
        }

        logger.info { "Successfully pulled model: $model" }
    }

    /**
     * Ensures the Ollama server is running and the specified model is available.
     *
     * This is the main convenience method that handles all setup:
     * 1. Starts the server if not running (when [startServer] is true)
     * 2. Pulls the model if not available (when [pullModel] is true)
     *
     * @param model Model name to ensure is available
     * @param startServer If true, starts the server if not running
     * @param pullModel If true, pulls the model if not available
     * @param onProgress Callback for pull progress updates
     */
    public suspend fun ensureReady(
        model: String,
        startServer: Boolean = true,
        pullModel: Boolean = true,
        onProgress: (OllamaPullProgress) -> Unit = { progress ->
            progress.status?.let { logger.info { "Ollama: $it" } }
        }
    ) {
        // Start server if needed
        if (startServer && !isServerRunning()) {
            if (!isInstalled()) {
                throw IllegalStateException(
                    "Ollama is not installed. Please install it from https://ollama.ai/download"
                )
            }
            startServer()
        }

        // Verify server is running
        if (!isServerRunning()) {
            throw IllegalStateException(
                "Ollama server is not running at $baseUrl. " +
                    "Start it with 'ollama serve' or set autoStart=true"
            )
        }

        // Pull model if needed
        if (pullModel && !isModelPulled(model)) {
            pullModel(model, onProgress)
        }

        // Final verification
        if (!isModelPulled(model)) {
            throw IllegalStateException(
                "Model '$model' is not available. " +
                    "Pull it with 'ollama pull $model' or set autoPull=true"
            )
        }

        logger.info { "Ollama ready with model: $model" }
    }

    /**
     * Normalizes model names for comparison.
     *
     * Handles variations like "llama3.2" vs "llama3.2:latest"
     */
    private fun normalizeModelName(name: String): String {
        val baseName = name.substringBefore(":")
        val tag = name.substringAfter(":", "latest")
        return if (tag == "latest") baseName else "$baseName:$tag"
    }

    /**
     * Closes resources used by this manager.
     *
     * Note: This does NOT stop a server that was started by this manager.
     * Use [stopServer] explicitly if you want to stop the server.
     */
    public fun close() {
        httpClient.close()
    }
}

// ============================================================================
// API Response Models
// ============================================================================

@Serializable
internal data class OllamaTagsResponse(
    val models: List<OllamaModelInfo> = emptyList()
)

/**
 * Information about an Ollama model.
 */
@Serializable
public data class OllamaModelInfo(
    val name: String,
    val modified_at: String? = null,
    val size: Long? = null,
    val digest: String? = null,
    val details: OllamaModelDetails? = null
)

@Serializable
public data class OllamaModelDetails(
    val format: String? = null,
    val family: String? = null,
    val families: List<String>? = null,
    val parameter_size: String? = null,
    val quantization_level: String? = null
)

@Serializable
internal data class OllamaPullRequest(
    val name: String,
    val insecure: Boolean = false,
    val stream: Boolean = true
)

/**
 * Progress update during model pull operation.
 */
@Serializable
public data class OllamaPullProgress(
    val status: String? = null,
    val digest: String? = null,
    val total: Long? = null,
    val completed: Long? = null,
    val error: String? = null
) {
    /**
     * Download progress as a percentage (0-100), or null if not applicable.
     */
    val progressPercent: Double?
        get() = if (total != null && total > 0 && completed != null) {
            (completed.toDouble() / total.toDouble()) * 100
        } else {
            null
        }

    /**
     * Human-readable progress string.
     */
    val progressString: String
        get() = buildString {
            append(status ?: "Unknown status")
            progressPercent?.let { pct ->
                append(" (${String.format("%.1f", pct)}%)")
            }
        }
}
