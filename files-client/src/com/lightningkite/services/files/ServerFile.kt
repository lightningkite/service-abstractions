package com.lightningkite.services.files

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Type-safe wrapper for server-side file locations.
 *
 * Provides a value class that wraps file location strings with serialization support.
 * Used primarily for API responses and client-side file references.
 *
 * ## Features
 *
 * - **Value class**: Zero runtime overhead (just a String wrapper)
 * - **Type safety**: Prevents mixing up file locations with regular strings
 * - **Serialization**: Custom serializer delegates to contextual serialization
 * - **Multiplatform**: Works across JVM, JS, and Native targets
 *
 * ## Usage Pattern
 *
 * This type is typically used in API models to represent file references:
 *
 * ```kotlin
 * @Serializable
 * data class UserProfile(
 *     val userId: String,
 *     val avatar: ServerFile,
 *     val documents: List<ServerFile>
 * )
 *
 * // Server generates file locations
 * val profile = UserProfile(
 *     userId = "123",
 *     avatar = ServerFile("https://cdn.example.com/avatars/user-123.jpg"),
 *     documents = listOf(
 *         ServerFile("https://cdn.example.com/docs/resume.pdf"),
 *         ServerFile("https://cdn.example.com/docs/certificate.pdf")
 *     )
 * )
 * ```
 *
 * ## Serialization
 *
 * The `DeferToContextualServerFileSerializer` allows customization of how ServerFile
 * instances are serialized. This enables different serialization strategies:
 *
 * - **Direct URLs**: Serialize as plain URL strings
 * - **Signed URLs**: Generate temporary signed URLs on serialization
 * - **Relative paths**: Convert to relative paths for specific clients
 * - **Custom transforms**: Apply any transformation logic via context
 *
 * ## Important Notes
 *
 * - **Value class**: Inline class optimized away at runtime (no boxing)
 * - **JvmInline**: Uses Kotlin's inline value class feature
 * - **Contextual serialization**: Serialization behavior configurable via SerializersModule
 * - **No validation**: Location string not validated (could be URL, path, identifier, etc.)
 *
 * @property location The file location (URL, path, or identifier)
 */
@JvmInline
@Serializable(DeferToContextualServerFileSerializer::class)
public value class ServerFile(public val location: String)
