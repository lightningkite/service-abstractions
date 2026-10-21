package com.lightningkite.services.database.sqlite

import platform.Foundation.NSTemporaryDirectory

internal actual fun testDatabaseLocation(fileName: String): String = NSTemporaryDirectory() + fileName
