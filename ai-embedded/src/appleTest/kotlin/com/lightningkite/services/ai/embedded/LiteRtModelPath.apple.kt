package com.lightningkite.services.ai.embedded

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun liteRtModelPath(): String? = getenv("LITERT_MODEL")?.toKString()
