package com.lightningkite.services.files

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@JvmInline
@Serializable(DeferToContextualServerFileSerializer::class)
public value class ServerFile(public val location: String)
