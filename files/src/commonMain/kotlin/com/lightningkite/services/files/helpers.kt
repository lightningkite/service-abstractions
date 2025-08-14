package com.lightningkite.services.files

import com.lightningkite.services.data.KFile
import com.lightningkite.services.data.TypedData
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.uuid.Uuid

public suspend fun FileObject.download(
    file: KFile?
): KFile? = get()?.let { download(file ?: KFile.temporary(extension = it.mediaType.extension)) }
public suspend fun TypedData.download(
    file: KFile = KFile.temporary(extension = mediaType.extension)
): KFile {
    write(file.sink())
    return file
}