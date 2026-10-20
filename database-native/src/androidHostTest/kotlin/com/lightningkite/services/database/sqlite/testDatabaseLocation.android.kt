package com.lightningkite.services.database.sqlite

import java.io.File

internal actual fun testDatabaseLocation(fileName: String): String =
    File(System.getProperty("java.io.tmpdir")).resolve(fileName).absolutePath
