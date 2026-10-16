package com.lightningkite.services.database

import kotlin.test.*

class TextQueryTest {
    @Test
    fun parsesWordsPhrasesAndExclusions() {
        assertEquals(
            TextQuery(exact = setOf("type safe"), fuzzy = setOf("kotlin", "e-mail"), reject = setOf("java", "old stuff")),
            TextQuery.fromString("Kotlin \"Type Safe\" e-mail -java -\"old stuff\""),
        )
    }

    @Test
    fun queryWithoutWordsMatchesNothing() {
        assertNull(TextQuery.fromString("").score("anything"))
        assertNull(TextQuery.fromString("-other").score("anything"))
    }

    @Test
    fun hyphenatedWordsMatchTheirParts() {
        assertNotNull(TextQuery.fromString("e-mail").score("send an e-mail"))
        assertNotNull(TextQuery.fromString("e-mail").score("send an e mail"))
    }

    @Test
    fun numbersMustMatchExactly() {
        assertNull(TextQuery.fromString("2024").score("released in 2025"))
    }

    @Test
    fun scoreFallsWithEdits() {
        val query = TextQuery.fromString("programming")
        val exact = query.score("programming")!!
        val oneEdit = query.score("programing")!!
        val twoEdits = query.score("progamin")
        assertEquals(1f, exact)
        assertTrue(oneEdit < exact)
        assertNull(twoEdits, "three edits is too many even for a long word")
    }
}
