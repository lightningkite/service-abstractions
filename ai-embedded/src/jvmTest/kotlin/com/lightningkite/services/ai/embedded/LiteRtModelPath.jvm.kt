package com.lightningkite.services.ai.embedded

internal actual fun liteRtModelPath(): String? = System.getenv("LITERT_MODEL")
