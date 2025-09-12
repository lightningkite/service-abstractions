package com.lightningkite.services.files

import com.lightningkite.services.data.KFile
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.temporary
import kotlinx.io.files.SystemFileSystem
import kotlin.uuid.Uuid

public suspend fun FileObject.download(
    file: KFile?
): KFile? = get()?.let { download(file ?: SystemFileSystem.temporary(extension = it.mediaType.extension)) }
public suspend fun TypedData.download(
    file: KFile = SystemFileSystem.temporary(extension = mediaType.extension)
): KFile {
    write(file.sink())
    return file
}

@Deprecated("Use then", ReplaceWith("then(path)"))
public fun FileObject.resolve(path: String): FileObject = then(path)
@Deprecated("Use thenRandom", ReplaceWith("thenRandom(prefix, extension)"))
public fun FileObject.resolveRandom(prefix: String, extension: String): FileObject = thenRandom(prefix, extension)
public fun FileObject.thenRandom(prefix: String, extension: String): FileObject {
    return then("${prefix}_${Uuid.random()}.${extension}")
}

public val FileObject.serverFile: ServerFile get() = ServerFile(url)