package com.lightningkite.services.speech.local

import com.lightningkite.MediaType
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.speech.TranscriptionOptions
import com.lightningkite.services.speech.TtsSynthesisOptions
import com.lightningkite.services.speech.TtsVoiceConfig
import kotlinx.coroutines.runBlocking
import java.io.File

object PracticalTest {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val context = TestSettingContext()

        // Create output directory
        val outputDir = File("./local/speech-test-output")
        outputDir.mkdirs()
        val audioFile = File(outputDir, "test-speech.wav")

        // Step 1: Use FreeTTS to create a sample audio file
        println("=== FreeTTS Text-to-Speech ===")
        val tts = FreeTtsTextToSpeechService("test-tts", context)

        val textToSpeak = "Hello, this is a test of the local speech system. One two three four five."
        println("Synthesizing text: \"$textToSpeak\"")

        val audioResult = tts.synthesize(
            text = textToSpeak,
            voice = TtsVoiceConfig(voiceId = "kevin16"),
            options = TtsSynthesisOptions()
        )

        // Save the audio to a file
        val audioBytes = audioResult.data.bytes()
        audioFile.writeBytes(audioBytes)
        println("Audio saved to: ${audioFile.absolutePath} (${audioBytes.size} bytes)")

        // Step 2: Use Vosk to transcribe the file
        println()
        println("=== Vosk Speech-to-Text ===")
        println("Note: This will download the Vosk model (~40MB) on first run...")

        val stt = VoskSpeechToTextService(
            name = "test-stt",
            context = context,
            modelPath = null,
            modelName = VoskModelManager.DEFAULT_MODEL_NAME,
            modelsDirectory = File("./local/vosk-models")
        )

        // Read the audio file back and transcribe
        val audioData = TypedData(
            Data.Bytes(audioFile.readBytes()),
            MediaType.Audio.WAV
        )

        println("Transcribing audio file...")
        val transcriptionResult = stt.transcribe(
            audio = audioData,
            options = TranscriptionOptions(wordTimestamps = true)
        )

        println()
        println("=== Transcription Result ===")
        println("Text: ${transcriptionResult.text}")
        println("Language: ${transcriptionResult.language}")
        if (transcriptionResult.words.isNotEmpty()) {
            println("Words with timestamps:")
            transcriptionResult.words.forEach { word ->
                println("  [${word.startTime} - ${word.endTime}] ${word.text} (confidence: ${word.confidence ?: "N/A"})")
            }
        }

        // Cleanup
        stt.disconnect()

        println()
        println("=== Test Complete ===")
        println("Original text: \"$textToSpeak\"")
        println("Transcribed:   \"${transcriptionResult.text}\"")
    }
}
