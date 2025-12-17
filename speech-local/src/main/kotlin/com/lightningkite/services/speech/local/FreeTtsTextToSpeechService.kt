package com.lightningkite.services.speech.local

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.speech.*
import com.sun.speech.freetts.Voice
import com.sun.speech.freetts.VoiceManager
import com.sun.speech.freetts.audio.SingleFileAudioPlayer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.sound.sampled.AudioFileFormat

private val logger = KotlinLogging.logger("FreeTtsTextToSpeechService")

/**
 * Local, offline text-to-speech implementation using FreeTTS.
 *
 * FreeTTS is a pure Java speech synthesis engine. It provides basic TTS
 * functionality without requiring any external services or API keys.
 *
 * ## URL Format
 *
 * ```
 * freetts://
 * local://  (alias)
 * ```
 *
 * No configuration parameters are required.
 *
 * ## Available Voices
 *
 * FreeTTS includes limited voices:
 * - `kevin16` - General purpose male voice at 16kHz (default)
 * - `kevin` - General purpose male voice at 8kHz
 * - `alan` - Limited vocabulary voice (digits only)
 *
 * ## Limitations
 *
 * - **English only**: FreeTTS only supports English
 * - **Male voices only**: No female or neutral voice options
 * - **Basic quality**: Voice quality is robotic compared to cloud services
 * - **No SSML**: SSML markup is not supported
 * - **No streaming**: Audio is generated in full before returning
 *
 * This implementation is intended for local testing and development,
 * not production use.
 *
 * @see TextToSpeechService
 */
public class FreeTtsTextToSpeechService(
    override val name: String,
    override val context: SettingContext
) : TextToSpeechService {

    private val availableVoices = listOf(
        VoiceInfo(
            voiceId = "kevin16",
            name = "Kevin (16kHz)",
            language = "en-US",
            gender = TtsGender.MALE,
            description = "General purpose male voice at 16kHz sample rate"
        ),
        VoiceInfo(
            voiceId = "kevin",
            name = "Kevin (8kHz)",
            language = "en-US",
            gender = TtsGender.MALE,
            description = "General purpose male voice at 8kHz sample rate (telephony)"
        )
    )

    init {
        // Register FreeTTS voices
        System.setProperty(
            "freetts.voices",
            "com.sun.speech.freetts.en.us.cmu_us_kal.KevinVoiceDirectory"
        )
    }

    override suspend fun listVoices(language: String?): List<VoiceInfo> {
        // FreeTTS only supports English
        return if (language == null || language.startsWith("en")) {
            availableVoices
        } else {
            emptyList()
        }
    }

    override suspend fun synthesize(
        text: String,
        voice: TtsVoiceConfig,
        options: TtsSynthesisOptions
    ): TypedData = withContext(Dispatchers.IO) {
        val voiceId = voice.voiceId ?: "kevin16"

        logger.debug { "[$name] Synthesizing ${text.length} chars with voice=$voiceId" }

        val audioBytes = synthesizeToWav(text, voiceId, options.speed)

        // Convert to requested format if needed
        val (outputBytes, mediaType) = convertAudioFormat(audioBytes, options.outputFormat)

        logger.debug { "[$name] Synthesized ${outputBytes.size} bytes of audio" }
        TypedData(Data.Bytes(outputBytes), mediaType)
    }

    override fun synthesizeStream(
        text: String,
        voice: TtsVoiceConfig,
        options: TtsSynthesisOptions
    ): Flow<TypedData> = flow {
        // FreeTTS doesn't support streaming, so emit full audio as single chunk
        val result = synthesize(text, voice, options)
        emit(result)
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            val voiceManager = VoiceManager.getInstance()
            val voice = voiceManager.getVoice("kevin16")
            if (voice != null) {
                HealthStatus(HealthStatus.Level.OK, additionalMessage = "FreeTTS ready")
            } else {
                HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "FreeTTS voice not found")
            }
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "FreeTTS error: ${e.message}")
        }
    }

    private fun synthesizeToWav(text: String, voiceId: String, speed: Float): ByteArray {
        val voiceManager = VoiceManager.getInstance()
        val voice = voiceManager.getVoice(voiceId)
            ?: throw TextToSpeechException("Voice not found: $voiceId")

        // Create a temp file for output - SingleFileAudioPlayer writes to file
        val tempDir = File("./local/freetts-temp")
        tempDir.mkdirs()
        val tempBaseName = "${tempDir.absolutePath}/tts_${System.currentTimeMillis()}"
        val tempFile = File("$tempBaseName.wav")

        voice.allocate()
        try {
            // Adjust rate based on speed parameter
            // FreeTTS rate is words per minute, default is around 150
            val defaultRate = 150f
            voice.rate = (defaultRate * speed).coerceIn(50f, 400f)

            // Use SingleFileAudioPlayer which properly handles WAV format
            val audioPlayer = SingleFileAudioPlayer(tempBaseName, AudioFileFormat.Type.WAVE)
            voice.audioPlayer = audioPlayer

            voice.speak(text)

            // Must close to flush and finalize the WAV file
            audioPlayer.close()

            // Read the file back as bytes
            val audioBytes = tempFile.readBytes()

            return audioBytes
        } finally {
            voice.deallocate()
            // Clean up temp file
            tempFile.delete()
        }
    }

    private fun convertAudioFormat(
        wavBytes: ByteArray,
        targetFormat: com.lightningkite.services.speech.AudioFormat
    ): Pair<ByteArray, MediaType> {
        // For now, we return WAV for all formats since FreeTTS produces WAV
        // A more complete implementation would convert to other formats
        return when (targetFormat) {
            com.lightningkite.services.speech.AudioFormat.WAV_44100,
            com.lightningkite.services.speech.AudioFormat.PCM_16000,
            com.lightningkite.services.speech.AudioFormat.PCM_22050,
            com.lightningkite.services.speech.AudioFormat.PCM_24000,
            com.lightningkite.services.speech.AudioFormat.PCM_44100 -> {
                wavBytes to MediaType.Audio.WAV
            }
            else -> {
                // Return WAV for unsupported formats with a warning
                logger.warn { "[$name] Format $targetFormat not supported, returning WAV" }
                wavBytes to MediaType.Audio.WAV
            }
        }
    }

    public companion object {
        init {
            TextToSpeechService.Settings.register("freetts") { name, _, context ->
                FreeTtsTextToSpeechService(name, context)
            }
            TextToSpeechService.Settings.register("local") { name, _, context ->
                FreeTtsTextToSpeechService(name, context)
            }
        }
    }
}
