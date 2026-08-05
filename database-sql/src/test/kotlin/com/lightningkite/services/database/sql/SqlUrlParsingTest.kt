package com.lightningkite.services.database.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression coverage for the `sql-mysql://`/`sql-mariadb://` settings URL parser (release review
 * finding 9, HIGH): the original hand-rolled regex had a literal `:` separator (unlike the Postgres
 * regex bug, finding 2) but couldn't represent a password containing `@` — it bound `host` starting
 * from the first `@` rather than the last, and never percent-decoded the credentials.
 */
class SqlUrlParsingTest {
    @Test
    fun `known-good url parses user and password correctly`() {
        val parsed = parseSqlAuthUrl("sql-mysql://myuser:mypass@host:3306/db")
        assertEquals("myuser", parsed?.user)
        assertEquals("mypass", parsed?.password)
        assertEquals("host:3306/db", parsed?.destination)
    }

    @Test
    fun `password containing an at sign is decoded correctly`() {
        // '@' must be percent-encoded within the password so the URL grammar can find the real
        // user-info/host boundary; a regex without a real grammar bound the match at the wrong '@'.
        val parsed = parseSqlAuthUrl("sql-mysql://myuser:my%40pass@host:3306/db")
        assertEquals("myuser", parsed?.user)
        assertEquals("my@pass", parsed?.password)
        assertEquals("host:3306/db", parsed?.destination)
    }

    @Test
    fun `password containing a colon is preserved in full`() {
        val parsed = parseSqlAuthUrl("sql-mariadb://myuser:my:pass@host:3306/db")
        assertEquals("myuser", parsed?.user)
        assertEquals("my:pass", parsed?.password)
        assertEquals("host:3306/db", parsed?.destination)
    }

    @Test
    fun `url without user info fails to parse`() {
        assertNull(parseSqlAuthUrl("sql-mysql://host:3306/db"))
    }

    @Test
    fun `query params are preserved on the destination`() {
        val parsed = assertNotNull(parseSqlAuthUrl("sql-mysql://myuser:mypass@host:3306/db?maxPoolSize=20"))
        assertEquals("host:3306/db?maxPoolSize=20", parsed.destination)
    }
}
