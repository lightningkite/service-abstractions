package com.lightningkite.services.database.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal actual fun databasePath(location: String): String {
    if (location.startsWith("/")) return location
    val directory = NSFileManager.defaultManager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
        ?: error("The Application Support directory isn't available")
    return directory.path!! + "/" + location
}
