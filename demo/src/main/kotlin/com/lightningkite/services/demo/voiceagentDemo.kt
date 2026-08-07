package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.voiceagent.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates the voice agent subsystem via `test://`: open a session, send it a user
 * message, then trigger a response and print the events the fake provider emits.
 */
fun main() = runBlocking {
    val context = TestSettingContext()
    val voiceAgent = VoiceAgentService.Settings("test://")("agent", context)

    val session = voiceAgent.createSession(VoiceAgentSessionConfig(instructions = "You are a helpful assistant."))
    session.awaitConnection()

    val collector = launch {
        session.events.collect { event ->
            println("Event: $event")
            if (event is VoiceAgentEvent.ResponseDone) session.close()
        }
    }

    session.addMessage(VoiceAgentSession.MessageRole.User, "Hello!")
    session.createResponse()
    collector.join()
}
