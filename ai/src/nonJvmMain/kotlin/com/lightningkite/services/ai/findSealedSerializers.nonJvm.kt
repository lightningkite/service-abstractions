package com.lightningkite.services.ai

import kotlinx.serialization.KSerializer

/**
 * Non-JVM actual: reflection is unavailable on these targets. [sealedSchema] falls back to
 * a variant-name-only schema. In practice this only matters for plain
 * `kotlinx.serialization.internal.SealedClassSerializer` usages — our own
 * [MySealedClassSerializerInterface][com.lightningkite.services.database.MySealedClassSerializerInterface]
 * exposes its options directly, so Condition/Modification and user-defined single-key sealed
 * unions work fine here.
 */
public actual fun findSealedSerializers(serializer: KSerializer<*>): Map<String, KSerializer<*>>? = null
