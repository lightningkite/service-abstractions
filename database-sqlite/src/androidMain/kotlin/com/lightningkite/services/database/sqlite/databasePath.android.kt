package com.lightningkite.services.database.sqlite

internal actual fun databasePath(location: String): String {
    require(location.startsWith("/")) {
        "native:// needs an absolute path on Android, e.g. context.getDatabasePath(\"app.db\").path; got '$location'"
    }
    return location
}
