package com.lightningkite.services.data

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.bytestring.ByteString
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.readByteString
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.uuid.Uuid

public data class KFile(public val fileSystem: FileSystem, public val path: Path) {
    constructor(string: String): this(SystemFileSystem, Path(string))

    override fun toString(): String {
        return if(fileSystem == SystemFileSystem) path.toString()
        else "${fileSystem}:$path"
    }

    public val name get() = path.name
    public val extension get() = path.name.substringAfterLast('.', "")
    public val nameWithoutExtension get() = path.name.substringBeforeLast('.')
    public val parent: KFile? get() = path.parent?.let { KFile(fileSystem, it) }
    public val resolved: KFile get() = KFile(fileSystem, fileSystem.resolve(path))

    public fun then(vararg parts: String): KFile = KFile(fileSystem, Path(path, *parts))

    public fun resolve(string: String) = then(*string.split('/').toTypedArray())

    public fun sink(append: Boolean = false): Sink = fileSystem.sink(path, append).buffered()
    public fun source(): Source = fileSystem.source(path).buffered()

    public fun exists(): Boolean = fileSystem.exists(path)
    public fun metadataOrNull(): FileMetadata? = fileSystem.metadataOrNull(path)

    @Deprecated("Use createDirectories instead", ReplaceWith("createDirectories()"))
    public fun mkdirs(): Unit = fileSystem.createDirectories(path, false)

    public fun createDirectories(mustCreate: Boolean = false): Unit = fileSystem.createDirectories(path, mustCreate)
    public fun delete(mustExist: Boolean = false): Unit = fileSystem.delete(path, mustExist)
    public fun deleteRecursively(mustExist: Boolean = false): Unit {
        val m = metadataOrNull() ?: if(mustExist) throw kotlinx.io.files.FileNotFoundException("$this does not exist.") else return
        if(m.isDirectory) {
            list().forEach { it.deleteRecursively() }
            delete(mustExist)
        } else delete(mustExist)
    }
    public fun atomicMove(destination: KFile): Unit {
        if(fileSystem != destination.fileSystem) throw IllegalArgumentException("Different file systems")
        fileSystem.atomicMove(path, destination.path)
    }
    public fun list(): Collection<KFile> {
        return fileSystem.list(path).map { KFile(fileSystem, it) }
    }

    public fun writeText(string: String) = sink().use { it.writeString(string) }
    public fun appendText(string: String) = sink(append = true).use { it.writeString(string) }
    public fun writeByteArray(byteArray: ByteArray) = sink().use { it.write(byteArray) }
    public fun appendByteArray(byteArray: ByteArray) = sink(append = true).use { it.write(byteArray) }
    public fun writeByteString(byteString: ByteString) = sink().use { it.write(byteString.toByteArray()) }
    public fun appendByteString(byteString: ByteString) = sink(append = true).use { it.write(byteString.toByteArray()) }

    public fun readString(): String = source().use { it.readString() }
    public fun readByteArray(): ByteArray = source().use { it.readByteArray() }
    public fun readByteString(): ByteString = source().use { it.readByteString() }
    public fun readStringOrNull(): String? = if(exists()) readString() else null
    public fun readByteArrayOrNull(): ByteArray? = if(exists()) readByteArray() else null
    public fun readByteStringOrNull(): ByteString? = if(exists()) readByteString() else null

    public fun takeIfExists(): KFile? = takeIf { fileSystem.exists(path) }
}

public val FileSystem.root: KFile get() = KFile(this, Path(""))
public fun FileSystem.temporary(extension: String = "file", leading: String? = null): KFile = KFile(this,
    Path(SystemTemporaryDirectory, (leading ?: Uuid.random().toString()) + "." + extension)
)

private fun sampleUsage() {
    SystemFileSystem.root.then("Home", "Joseph", "sample.txt").writeText("Hello world!")
}