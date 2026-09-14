@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.lightningkite.services.ai

import kotlinx.serialization.SerialInfo

/**
 * Mark a property as invisible to the LLM. It is dropped from generated tool
 * input schemas entirely — the model cannot read or write it.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.CLASS)
public annotation class HideFromLlm

/**
 * Mark a property as read-only to the LLM. Currently treated the same as
 * [HideFromLlm] in tool input schemas (the model is not asked to supply it);
 * reserved for future use where the value may be surfaced back as read-only
 * context.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.CLASS)
public annotation class LlmReadOnly

/**
 * Applied to a class whose fields should be treated as a one-of (exactly one
 * non-null). The generated schema is an `anyOf` of single-property objects
 * rather than an object with all fields.
 *
 * Each property of the annotated class must be nullable; the unwrapped inner
 * type is used as the schema for that branch.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class MutuallyExclusive
