package com.lightningkite.services

import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock

public class TestSettingContext(
    override val internalSerializersModule: SerializersModule = EmptySerializersModule(),
    override var clock: Clock = Clock.System
): SettingContext {
    override val sharedResources: SharedResources = SharedResources()
    override val projectName: String get() = "Test"
    override val openTelemetry: OpenTelemetry? get() = null
}