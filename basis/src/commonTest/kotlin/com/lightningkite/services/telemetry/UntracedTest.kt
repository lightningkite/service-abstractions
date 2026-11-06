package com.lightningkite.services.telemetry

import com.lightningkite.services.Namespaced
import com.lightningkite.services.SettingContext
import com.lightningkite.services.SharedResources
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals

class UntracedTest {
    private val recorded = ArrayList<String>()
    private val recording = object : TelemetryBackend by TelemetryBackend.Noop {
        override suspend fun <T> span(owner: Namespaced, opName: String, attributes: TelemetryAttributes, dimensions: Set<TelemetryKey<*>>, action: suspend (TelemetryTrace) -> T): T {
            recorded += opName
            return TelemetryBackend.Noop.span(owner, opName, attributes, dimensions, action)
        }
    }
    private val owner = object : Namespaced {
        override val name = "test"
        override val context: SettingContext = object : SettingContext {
            override val projectName = "test"
            override val publicUrl = ""
            override val internalSerializersModule: SerializersModule = EmptySerializersModule()
            override val sharedResources = SharedResources()
            override val telemetryBackend: TelemetryBackend = recording
        }
    }

    @Test
    fun spansUnderUntracedAreNotRecorded() = runTest {
        owner.telemetryTrace("traced") {}
        withContext(Untraced) { owner.telemetryTrace("outer") { owner.telemetryTrace("inner") {} } }
        assertEquals(listOf("traced"), recorded)
    }
}
