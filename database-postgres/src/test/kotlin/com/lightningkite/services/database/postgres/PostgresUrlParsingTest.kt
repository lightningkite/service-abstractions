package com.lightningkite.services.database.postgres

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression coverage for the `postgresql://user:password@host` settings URL parser
 * (release review finding 2, BLOCKER): the original regex had no literal `:` between the
 * `user` and `password` named groups, so every password came out with a spurious leading `:`,
 * which HikariCP then used verbatim and broke authentication for essentially every documented
 * `postgresql://user:pass@host` deployment.
 */
class PostgresUrlParsingTest {
    @Test
    fun `known-good url parses user and password correctly`() {
        val parsed = parsePostgresAuthUrl("postgresql://myuser:mypass@host:5432/db")
        assertEquals("myuser", parsed?.user)
        assertEquals("mypass", parsed?.password)
        assertEquals("host:5432/db", parsed?.destination)
    }

    @Test
    fun `password containing an at sign is decoded correctly`() {
        // '@' must be percent-encoded within the password so the URL grammar can find the
        // real user-info/host boundary.
        val parsed = parsePostgresAuthUrl("postgresql://myuser:my%40pass@host:5432/db")
        assertEquals("myuser", parsed?.user)
        assertEquals("my@pass", parsed?.password)
        assertEquals("host:5432/db", parsed?.destination)
    }

    @Test
    fun `password containing a colon is preserved in full`() {
        val parsed = parsePostgresAuthUrl("postgresql://myuser:my:pass@host:5432/db")
        assertEquals("myuser", parsed?.user)
        assertEquals("my:pass", parsed?.password)
        assertEquals("host:5432/db", parsed?.destination)
    }

    @Test
    fun `no-auth local connection url has null user and password`() {
        val parsed = assertNotNull(parsePostgresAuthUrl("postgresql://host:5432/db"))
        assertNull(parsed.user)
        assertNull(parsed.password)
        assertEquals("host:5432/db", parsed.destination)
    }
}
