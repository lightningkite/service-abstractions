package com.lightningkite.services.kfile

import kotlinx.io.*
import kotlinx.io.bytestring.ByteString
import kotlinx.io.files.*
import kotlin.uuid.Uuid

public data class KFile(public val fileSystem: FileSystem, public val path: Path) {
    public constructor(string: String) : this(SystemFileSystem, Path(string))

    override fun toString(): String {
        return if (fileSystem == SystemFileSystem) path.toString()
        else "${fileSystem}:$path"
    }

    public val name: String get() = path.name
    public val extension: String get() = path.name.substringAfterLast('.', "")
    public val nameWithoutExtension: String get() = path.name.substringBeforeLast('.')
    public val parent: KFile? get() = path.parent?.let { KFile(fileSystem, it) }
    public val resolved: KFile get() = KFile(fileSystem, fileSystem.resolve(path))

    public fun then(vararg parts: String): KFile = KFile(fileSystem, Path(path, *parts))
    public fun withAlteredName(alter: (String) -> String): KFile = KFile(
        fileSystem = fileSystem,
        path = path.parent?.let { Path(it, alter(name)) } ?: Path(alter(name))
    )

    public fun withAlteredExtension(alter: (String) -> String): KFile =
        withAlteredName { it.substringBeforeLast('.') + "." + alter(it.substringAfterLast('.')) }

    public fun resolve(string: String): KFile = then(*string.split('/').toTypedArray())

    public fun sink(append: Boolean = false): Sink = fileSystem.sink(path, append).buffered()
    public fun source(): Source = fileSystem.source(path).buffered()

    public fun exists(): Boolean = fileSystem.exists(path)
    public fun metadataOrNull(): FileMetadata? = fileSystem.metadataOrNull(path)

    @Deprecated("Use createDirectories instead", ReplaceWith("createDirectories()"))
    public fun mkdirs(): Unit = fileSystem.createDirectories(path, false)

    public fun createDirectories(mustCreate: Boolean = false): Unit = fileSystem.createDirectories(path, mustCreate)
    public fun delete(mustExist: Boolean = false): Unit = fileSystem.delete(path, mustExist)
    public fun deleteRecursively(mustExist: Boolean = false): Unit {
        val m = metadataOrNull() ?: if (mustExist) throw FileNotFoundException("$this does not exist.") else return
        if (m.isDirectory) {
            list().forEach { it.deleteRecursively() }
            delete(mustExist)
        } else delete(mustExist)
    }

    public fun atomicMove(destination: KFile): Unit {
        if (fileSystem != destination.fileSystem) throw IllegalArgumentException("Different file systems")
        fileSystem.atomicMove(path, destination.path)
    }

    public fun list(): Collection<KFile> {
        return fileSystem.list(path).map { KFile(fileSystem, it) }
    }

    public fun writeString(string: String): Unit = sink().use { it.writeString(string) }
    public fun appendString(string: String): Unit = sink(append = true).use { it.writeString(string) }
    public fun writeByteArray(byteArray: ByteArray): Unit = sink().use { it.write(byteArray) }
    public fun appendByteArray(byteArray: ByteArray): Unit = sink(append = true).use { it.write(byteArray) }
    public fun writeByteString(byteString: ByteString): Unit = sink().use { it.write(byteString.toByteArray()) }
    public fun appendByteString(byteString: ByteString): Unit = sink(append = true).use { it.write(byteString.toByteArray()) }

    public fun readString(): String = source().use { it.readString() }
    public fun readByteArray(): ByteArray = source().use { it.readByteArray() }
    public fun readByteString(): ByteString = source().use { it.readByteString() }
    public fun readStringOrNull(): String? = if (exists()) readString() else null
    public fun readByteArrayOrNull(): ByteArray? = if (exists()) readByteArray() else null
    public fun readByteStringOrNull(): ByteString? = if (exists()) readByteString() else null

    public fun takeIfExists(): KFile? = takeIf { fileSystem.exists(path) }
    public fun copyTo(target: KFile, overwrite: Boolean = false): KFile {
        val sourceMetadata = this.metadataOrNull() ?: throw FileNotFoundException("File '$this' does not exist.")
        val targetMetadata = target.metadataOrNull()
        if (targetMetadata != null && !overwrite)
            throw FileAlreadyExistsException(target)
        if (sourceMetadata.isDirectory) {
            if (targetMetadata?.isRegularFile == true) throw FileAlreadyExistsException(target)
            target.createDirectories()
        } else {
            target.sink().use { source().use { src -> it.transferFrom(src) } }
        }
        return target
    }
}

public val FileSystem.root: KFile get() = KFile(this, Path("$SystemPathSeparator"))
public val workingDirectory: KFile get() = KFile(SystemFileSystem, Path(""))
public fun FileSystem.temporary(extension: String = "file", leading: String? = null): KFile = KFile(
    this,
    Path(SystemTemporaryDirectory, (leading ?: Uuid.random().toString()) + "." + extension)
)

public class FileAlreadyExistsException(
    file: KFile,
) : IOException("The file '${file}' already exists.")