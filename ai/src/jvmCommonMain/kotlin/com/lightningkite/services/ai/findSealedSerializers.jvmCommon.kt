package com.lightningkite.services.ai

import kotlinx.serialization.KSerializer

/**
 * JVM actual: pulls the private `serialName2Serializer` field off [kotlinx.serialization.internal.SealedClassSerializer]
 * (and its subclasses) via Java reflection. No public API exists to enumerate a sealed
 * serializer's concrete subclasses, so this is unavoidable until one lands upstream.
 */
@Suppress("UNCHECKED_CAST")
public actual fun findSealedSerializers(serializer: KSerializer<*>): Map<String, KSerializer<*>>? {
    var cls: Class<*>? = serializer::class.java
    while (cls != null) {
        try {
            val field = cls.getDeclaredField("serialName2Serializer")
            field.isAccessible = true
            return field.get(serializer) as Map<String, KSerializer<*>>
        } catch (e: NoSuchFieldException) {
            cls = cls.superclass
        }
    }
    return null
}
