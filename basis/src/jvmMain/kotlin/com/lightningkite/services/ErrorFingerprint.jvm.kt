package com.lightningkite.services

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import java.security.MessageDigest

private val errorFingerprintKey: AttributeKey<String> = AttributeKey.stringKey("error.fingerprint")

/**
 * Generates a stable fingerprint for error grouping in observability backends.
 *
 * The fingerprint is a SHA-256 hash of the exception class and the top 5 stack frames
 * (class + method only, no line numbers), so it remains stable across minor code changes.
 */
public fun Throwable.errorFingerprint(): String {
    val frames = stackTrace.take(5).joinToString("|") { "${it.className}.${it.methodName}" }
    val input = "${javaClass.name}|$frames"
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Records an exception on this span and sets an `error.fingerprint` attribute for stable error grouping.
 */
public fun Span.recordExceptionWithFingerprint(t: Throwable) {
    recordException(t)
    setAttribute(errorFingerprintKey, t.errorFingerprint())
}
