package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.speech.*
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates the speech subsystem via `test://`: synthesize text to audio with
 * text-to-speech, then feed that audio into speech-to-text, showing the two services compose.
 * (The fake speech-to-text provider returns a fixed transcript rather than truly transcribing
 * the audio - it only proves the two services can be wired together, not real accuracy.)
 */
fun main() = runBlocking {
    val context = TestSettingContext()
    val tts = TextToSpeechService.Settings("test://")("tts", context)
    val stt = SpeechToTextService.Settings("test://")("stt", context)

    val text = "Hello, how can I help you today?"
    val audio = tts.synthesize(text)
    println("Synthesized ${audio.data} as ${audio.mediaType}")

    val transcription = stt.transcribe(audio)
    println("Original text: $text")
    println("Transcribed text: ${transcription.text}")
}
