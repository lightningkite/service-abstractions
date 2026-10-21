package com.lightningkite.services.database.sqlite

import java.io.File

internal actual fun testDatabaseLocation(fileName: String): String =
    File("build/test-databases").also { it.mkdirs() }.resolve(fileName).path
