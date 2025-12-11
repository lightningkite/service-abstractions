package com.lightningkite.services.speech

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

private val logger = KotlinLogging.logger("ConsoleTextToSpeechService")

/**
 * Console implementation of [TextToSpeechService] for development.
 *
 * Logs all TTS requests to the console/logger and returns mock audio data.
 * Useful for development when you want to see what text would be synthesized
 * without actually calling a TTS provider.
 *
 * ```kotlin
 * val tts = ConsoleTextToSpeechService("dev-tts", context)
 * tts.synthesize("Hello world")
 * // Logs: [dev-tts] TTS synthesize: "Hello world" (voice=null, lang=en-US)
 * ```
 */
public class ConsoleTextToSpeechService(
    override val name: String,
    override val context: SettingContext
) : TextToSpeechService {

    override suspend fun listVoices(language: String?): List<VoiceInfo> {
        logger.info { "[$name] TTS listVoices(language=$language)" }
        return listOf(
            VoiceInfo(
                voiceId = "console-default",
                name = "Console Default Voice",
                language = language ?: "en-US",
                gender = TtsGender.NEUTRAL,
                description = "Mock voice for console development"
            )
        )
    }

    override suspend fun synthesize(
        text: String,
        voice: TtsVoiceConfig,
        options: TtsSynthesisOptions
    ): TypedData {
        val truncatedText = if (text.length > 100) "${text.take(100)}..." else text
        logger.info {
            "[$name] TTS synthesize: \"$truncatedText\" " +
                    "(voice=${voice.voiceId}, lang=${voice.language}, format=${options.outputFormat})"
        }

        // Return minimal valid audio for the requested format
        val data: ByteArray
        val mediaType: MediaType

        when (options.outputFormat) {
            AudioFormat.MP3_44100_128, AudioFormat.MP3_44100_192 -> {
                data = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
                mediaType = MediaType.Audio.MPEG
            }
            AudioFormat.WAV_44100 -> {
                data = "RIFF\u0000\u0000\u0000\u0000WAVEfmt ".encodeToByteArray()
                mediaType = MediaType.Audio.WAV
            }
            AudioFormat.OGG_OPUS -> {
                data = "OggS".encodeToByteArray()
                mediaType = MediaType.Audio.OGG
            }
            else -> {
                data = byteArrayOf(0x00, 0x00, 0x00, 0x00)
                mediaType = MediaType.Application.OctetStream
            }
        }

        return TypedData(Data.Bytes(data), mediaType)
    }

    override fun synthesizeStream(
        text: String,
        voice: TtsVoiceConfig,
        options: TtsSynthesisOptions
    ): Flow<TypedData> {
        val truncatedText = if (text.length > 100) "${text.take(100)}..." else text
        logger.info {
            "[$name] TTS synthesizeStream: \"$truncatedText\" " +
                    "(voice=${voice.voiceId}, lang=${voice.language})"
        }
        return flowOf(TypedData(Data.Bytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte())), MediaType.Audio.MPEG))
    }

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Console TTS service")
    }
}
