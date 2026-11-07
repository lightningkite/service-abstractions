package com.lightningkite.services.ai.openai

internal actual fun getEnv(name: String): String? = System.getenv(name)
