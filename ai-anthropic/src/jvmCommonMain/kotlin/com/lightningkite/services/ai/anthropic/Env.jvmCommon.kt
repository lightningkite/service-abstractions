package com.lightningkite.services.ai.anthropic

internal actual fun getEnv(name: String): String? = System.getenv(name)
