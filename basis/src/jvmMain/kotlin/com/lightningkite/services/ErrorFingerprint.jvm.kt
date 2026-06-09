package com.lightningkite.services

import java.security.MessageDigest

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
