package com.lightningkite.services.speech.local

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.speech.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger("VoskSpeechToTextService")

/**
 * Local, offline speech-to-text implementation using Vosk.
 *
 * Vosk is an offline speech recognition toolkit that provides accurate
 * transcription without requiring external services or API keys.
 *
 * ## URL Format
 *
 * ```
 * vosk://                                    # Auto-download default English model
 * vosk://?modelPath=/path/to/model           # Use existing model at path
 * vosk://?modelName=vosk-model-small-de-0.15 # Auto-download specific model
 * local://                                   # Alias for vosk://
 * ```
 *
 * Query parameters:
 * - `modelPath` - Path to an existing Vosk model directory
 * - `modelName` - Name of model to auto-download (default: vosk-model-small-en-us-0.15)
 * - `modelsDir` - Directory to store downloaded models (default: ./local/vosk-models)
 *
 * ## First-Time Setup
 *
 * On first use, the service will automatically download the specified model
 * (~40MB for the small English model). This may take a few minutes depending
 * on your connection speed.
 *
 * ## Audio Requirements
 *
 * Vosk works best with:
 * - Sample rate: 16kHz (required)
 * - Channels: Mono
 * - Format: PCM/WAV
 *
 * The service will attempt to convert other formats automatically.
 *
 * ## Supported Languages
 *
 * Vosk supports 20+ languages. Use `modelName` parameter to select:
 * - `vosk-model-small-en-us-0.15` - English (US)
 * - `vosk-model-small-de-0.15` - German
 * - `vosk-model-small-es-0.42` - Spanish
 * - `vosk-model-small-fr-0.22` - French
 * - And more at https://alphacephei.com/vosk/models
 *
 * ## Limitations
 *
 * - **Model download required**: First use requires downloading ~40MB model
 * - **No speaker diarization**: Basic Vosk doesn't identify speakers
 * - **Limited audio events**: No detection of laughter, music, etc.
 * - **English accuracy**: Best accuracy is with English; varies for other languages
 *
 * This implementation is intended for local testing and development,
 * not production use.
 *
 * @see SpeechToTextService
 */
public class VoskSpeechToTextService(
    override val name: String,
    override val context: SettingContext,
    private val modelPath: File?,
    private val modelName: String,
    private val modelsDirectory: File
) : SpeechToTextService {

    private val modelManager = VoskModelManager(modelsDirectory, modelName)

    @Volatile
    private var model: Model? = null

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            ensureModelLoaded()
        }
    }

    override suspend fun disconnect() {
        model?.close()
        model = null
    }

    override suspend fun transcribe(
        audio: TypedData,
        options: TranscriptionOptions
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        ensureModelLoaded()

        val audioBytes = audio.data.bytes()
        logger.debug { "[$name] Transcribing ${audioBytes.size} bytes of audio" }

        // Convert audio to 16kHz mono WAV if needed
        val wavBytes = convertToVoskFormat(audioBytes, audio.mediaType.toString())

        transcribeWav(wavBytes, options)
    }

    override suspend fun transcribeUrl(
        audioUrl: String,
        options: TranscriptionOptions
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        logger.debug { "[$name] Downloading audio from: $audioUrl" }

        val audioBytes = URL(audioUrl).readBytes()
        val mediaType = guessMediaType(audioUrl)

        transcribe(
            TypedData(
                Data.Bytes(audioBytes),
                MediaType(mediaType)
            ),
            options
        )
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            val modelDir = modelPath ?: modelManager.getModelPath()
            if (modelManager.isModelDownloaded(modelName) || (modelPath?.exists() == true)) {
                HealthStatus(HealthStatus.Level.OK, additionalMessage = "Vosk model available at: ${modelDir.absolutePath}")
            } else {
                HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "Vosk model not downloaded yet (will download on first use)")
            }
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Vosk error: ${e.message}")
        }
    }

    private fun ensureModelLoaded() {
        if (model != null) return

        synchronized(this) {
            if (model != null) return

            val modelDir = modelPath ?: modelManager.ensureModelAvailable(modelName)
            logger.info { "[$name] Loading Vosk model from: ${modelDir.absolutePath}" }

            model = Model(modelDir.absolutePath)
            logger.info { "[$name] Vosk model loaded successfully" }
        }
    }

    private fun transcribeWav(wavBytes: ByteArray, options: TranscriptionOptions): TranscriptionResult {
        val currentModel = model ?: throw SpeechToTextException("Model not loaded")

        Recognizer(currentModel, 16000f).use { recognizer ->
            // Enable word timestamps if requested
            if (options.wordTimestamps) {
                recognizer.setWords(true)
            }

            // Process audio in chunks
            val audioStream = AudioSystem.getAudioInputStream(ByteArrayInputStream(wavBytes))
            val buffer = ByteArray(4096)
            var bytesRead: Int

            while (audioStream.read(buffer).also { bytesRead = it } >= 0) {
                recognizer.acceptWaveForm(buffer, bytesRead)
            }

            // Get final result
            val resultJson = recognizer.finalResult
            return parseVoskResult(resultJson, options)
        }
    }

    private fun parseVoskResult(resultJson: String, options: TranscriptionOptions): TranscriptionResult {
        val jsonObject = json.parseToJsonElement(resultJson).jsonObject

        val text = jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""

        val words = if (options.wordTimestamps) {
            jsonObject["result"]?.jsonArray?.mapNotNull { wordElement ->
                val wordObj = wordElement.jsonObject
                val word = wordObj["word"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val start = wordObj["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val end = wordObj["end"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val conf = wordObj["conf"]?.jsonPrimitive?.floatOrNull

                TranscribedWord(
                    text = word,
                    startTime = start.seconds,
                    endTime = end.seconds,
                    confidence = conf
                )
            } ?: emptyList()
        } else {
            emptyList()
        }

        // Calculate duration from last word if available
        val duration = words.lastOrNull()?.endTime

        return TranscriptionResult(
            text = text,
            language = "en-US", // Vosk doesn't return detected language
            words = words,
            duration = duration
        )
    }

    private fun convertToVoskFormat(audioBytes: ByteArray, mediaType: String): ByteArray {
        try {
            val inputStream = ByteArrayInputStream(audioBytes)
            val audioInputStream = AudioSystem.getAudioInputStream(inputStream)
            val sourceFormat = audioInputStream.format

            // Target format: 16kHz, mono, 16-bit PCM
            val targetFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                16000f,
                16,
                1,
                2,
                16000f,
                false
            )

            // Check if conversion is needed
            if (isCompatibleFormat(sourceFormat)) {
                return audioBytes
            }

            // Convert to target format
            val convertedStream = AudioSystem.getAudioInputStream(targetFormat, audioInputStream)
            val outputStream = java.io.ByteArrayOutputStream()
            AudioSystem.write(convertedStream, javax.sound.sampled.AudioFileFormat.Type.WAVE, outputStream)
            return outputStream.toByteArray()
        } catch (e: Exception) {
            logger.warn { "[$name] Could not convert audio format: ${e.message}, trying raw input" }
            return audioBytes
        }
    }

    private fun isCompatibleFormat(format: AudioFormat): Boolean {
        return format.sampleRate == 16000f &&
                format.channels == 1 &&
                format.sampleSizeInBits == 16
    }

    private fun guessMediaType(url: String): String {
        val extension = url.substringAfterLast('.').lowercase()
        return when (extension) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "webm" -> "audio/webm"
            else -> "audio/wav"
        }
    }

    public companion object {
        init {
            SpeechToTextService.Settings.register("vosk") { name, url, context ->
                val params = parseUrlParams(url)
                val modelPath = params["modelPath"]?.let { File(it) }
                val modelName = params["modelName"] ?: VoskModelManager.DEFAULT_MODEL_NAME
                val modelsDir = params["modelsDir"]?.let { File(it) } ?: File("./local/vosk-models")

                VoskSpeechToTextService(name, context, modelPath, modelName, modelsDir)
            }
            SpeechToTextService.Settings.register("local") { name, url, context ->
                val params = parseUrlParams(url)
                val modelPath = params["modelPath"]?.let { File(it) }
                val modelName = params["modelName"] ?: VoskModelManager.DEFAULT_MODEL_NAME
                val modelsDir = params["modelsDir"]?.let { File(it) } ?: File("./local/vosk-models")

                VoskSpeechToTextService(name, context, modelPath, modelName, modelsDir)
            }
        }

        private fun parseUrlParams(url: String): Map<String, String> {
            val queryString = url.substringAfter("?", "")
            if (queryString.isEmpty()) return emptyMap()

            return queryString.split("&")
                .mapNotNull { param ->
                    val parts = param.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }
                .toMap()
        }
    }
}
