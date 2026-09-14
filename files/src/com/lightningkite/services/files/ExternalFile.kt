package com.lightningkite.services.files

import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration

/**
 * A reference to a location (which may or may not exist) within a [ExternalFileSystem].
 *
 * `ExternalFile` is a thin `(fileSystem, path)` pair - all operations delegate to the
 * owning [fileSystem]. Two `ExternalFile`s are equal if they point at the same path in
 * the same file system.
 *
 * ```kotlin
 * val fs: PublicFileSystem = ...
 * val file = fs.root.then("uploads/document.pdf")
 *
 * file.put(TypedData(pdfBytes, MediaType.Application.Pdf))
 * val content = file.get()  // null if it doesn't exist
 * file.delete()
 * ```
 *
 * @see ExternalFileSystem
 * @see ExternalPath
 */
public data class ExternalFile(public val fileSystem: ExternalFileSystem, public val path: ExternalPath) {
    public val name: String get() = path.name
    public val extension: String get() = path.extension
    public val nameWithoutExtension: String get() = path.nameWithoutExtension

    /**
     * The parent file, or `null` if this is the file system's root.
     */
    public val parent: ExternalFile? get() = path.parent?.let { ExternalFile(fileSystem, it) }

    public fun then(vararg parts: String): ExternalFile = ExternalFile(fileSystem, path.then(*parts))
    public inline fun withAlteredName(alter: (String) -> String): ExternalFile =
        ExternalFile(fileSystem, path.withAlteredName(alter))

    public fun withAlteredExtension(alter: (String) -> String): ExternalFile =
        withAlteredName { it.substringBeforeLast('.') + "." + alter(it.substringAfterLast('.')) }

    public suspend fun flow(): Flow<ExternalFile> = fileSystem.flow(path).map { ExternalFile(fileSystem, it) }
    public suspend fun list(): List<ExternalFile> = fileSystem.flow(path).map { ExternalFile(fileSystem, it) }.toList()
    public suspend fun head(): FileInfo? = fileSystem.head(path)
    public suspend fun put(content: TypedData): Unit = fileSystem.put(path, content)
    public suspend fun get(): TypedData? = fileSystem.get(path)
    public suspend fun getRange(range: LongRange): TypedData? = fileSystem.getRange(path, range)
    public suspend fun copyTo(other: ExternalFile): Unit = fileSystem.copyTo(path, other)
    public suspend fun moveTo(other: ExternalFile): Unit = fileSystem.moveTo(path, other)
    public suspend fun delete(): Unit = fileSystem.delete(path)

    /**
     * A signed URL for this file that can be used to access it securely.
     *
     * @param timeout How long the URL should remain valid. Defaults to the file system's
     * configured signed-URL duration.
     */
    public fun signUrl(timeout: Duration? = null): String = fileSystem.signUrl(path, timeout)

    /**
     * A signed URL for this file using the file system's default signed-URL duration.
     *
     * For file systems configured without signing, this is an unsigned URL.
     */
    public val signedUrl: String get() = signUrl(null)

    /**
     * Generates a signed URL that can be used to upload content to this file.
     */
    public fun uploadUrl(timeout: Duration): String = fileSystem.uploadUrl(path, timeout)

    /**
     * The canonical, backend-agnostic reference for this file: `sf://<file system name>/<path>`.
     *
     * This - not a backend-specific absolute URL - is what gets persisted (see
     * [com.lightningkite.services.files.serverFile]). Because it is keyed by the service's
     * [PublicFileSystem.name] rather than by where the bytes physically live, a stored value keeps
     * resolving after the storage backend is swapped (e.g. S3 to local), as long as the service
     * keeps the same name and the underlying files are moved across. The path is escaped by
     * [ExternalPath.toString], so any file name survives the round trip through [Parser].
     */
    override fun toString(): String = "$CANONICAL_PREFIX${fileSystem.name}/$path"

    public companion object {
        public const val CANONICAL_PREFIX: String = "sf://"
    }

    /**
     * Resolves stored references back into files: the canonical `sf://<name>/<path>` form that
     * [toString] emits, falling back to backend-specific legacy URLs for rows written before the
     * canonical form existed.
     *
     * **Server-internal only.** Both forms are unsigned, so a string that reached the server from a
     * client must never be resolved here - it would let the client name any file it likes. Use
     * [ExternalFileSystem.parseExternalUrl] for client input, which checks the signature.
     */
    public class Parser(systems: List<ExternalFileSystem>) {
        private val systems: Map<String, ExternalFileSystem> = systems.associateBy { it.name }

        init {
            require(this.systems.size == systems.size) {
                "File system names must be unique for a canonical reference to name exactly one of them."
            }
            require(systems.none { '/' in it.name }) {
                "A file system name used in a canonical reference must not contain '/'."
            }
        }

        /**
         * Resolves [string], or returns null if it names no known file system - an unrecognized URL,
         * or a canonical reference to a file system this server isn't configured with.
         *
         * A canonical reference that *is* ours but is malformed throws instead: it can only have come
         * from our own storage, so it is corrupt data rather than something to shrug off.
         */
        public fun parseOrNull(string: String): ExternalFile? {
            if (!string.startsWith(CANONICAL_PREFIX)) {
                return systems.values.firstNotNullOfOrNull { it.parseLegacyUrl(string) }
            }
            val nameEnd = string.indexOf('/', CANONICAL_PREFIX.length)
            require(nameEnd >= 0) { "Canonical reference is missing the '/' after the file system name." }
            val system = systems[string.substring(CANONICAL_PREFIX.length, nameEnd)] ?: return null
            return ExternalFile(system, ExternalPath.fromConservativeString(string.substring(nameEnd + 1)))
        }

        public fun parse(string: String): ExternalFile = parseOrNull(string)
            ?: throw IllegalArgumentException("No known file system matches '${string.substringBefore('?')}'")
    }
}
