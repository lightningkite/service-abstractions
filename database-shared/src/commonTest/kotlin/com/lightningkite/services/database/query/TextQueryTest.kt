@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import kotlin.test.*

class TextQueryTest {
    private fun TextQuery.scores(vararg texts: String) = scores(texts.map { listOf(it) })

    @Test
    fun parsesWordsPhrasesAndExclusions() {
        assertEquals(
            TextQuery(exact = setOf("type safe"), fuzzy = setOf("kotlin", "e-mail"), reject = setOf("java", "old stuff")),
            TextQuery.fromString("Kotlin \"Type Safe\" e-mail -java -\"old stuff\""),
        )
    }

    @Test
    fun queryWithoutWordsMatchesNothing() {
        assertEquals(listOf<Float?>(null), TextQuery.fromString("").scores("anything"))
        assertEquals(listOf<Float?>(null), TextQuery.fromString("-other").scores("anything"))
    }

    @Test
    fun hyphenatedWordsMatchTheirParts() {
        assertNotNull(TextQuery.fromString("e-mail").scores("send an e-mail").single())
        assertNotNull(TextQuery.fromString("e-mail").scores("send an e mail").single())
    }

    @Test
    fun numbersMustMatchExactly() {
        assertNull(TextQuery.fromString("2024").scores("released in 2025").single())
    }

    @Test
    fun typosScoreLowerAndHaveALimit() {
        val (exact, oneEdit, threeEdits) = TextQuery.fromString("programming").scores("programming", "programing", "progamin")
        assertTrue(oneEdit!! < exact!!)
        assertNull(threeEdits, "three edits is too many even for a long word")
    }

    @Test
    fun scoreRisesWithOccurrencesAndFallsWithLength() {
        val query = TextQuery.fromString("kotlin")
        val (twice, once) = query.scores("kotlin kotlin guide", "kotlin java guide")
        assertTrue(twice!! > once!!)
        val (short, long) = query.scores("kotlin", "kotlin coroutines flows")
        assertTrue(short!! > long!!)
    }

    @Test
    fun rarerWordsCountForMore() {
        val (moreKotlin, moreCoroutines, _, _) = TextQuery.fromString("kotlin coroutines")
            .scores("kotlin kotlin coroutines", "kotlin coroutines coroutines", "kotlin guide", "kotlin java")
        assertTrue(moreCoroutines!! > moreKotlin!!)
    }

    @Test
    fun repeatsCountForLessEachTime() {
        val (lopsided, balanced) = TextQuery.fromString("kotlin coroutines")
            .scores("kotlin kotlin kotlin kotlin kotlin coroutines", "kotlin kotlin kotlin coroutines coroutines coroutines")
        assertTrue(balanced!! > lopsided!!)
    }

    @Test
    fun phrasesMatchWithinOneFieldOnly() {
        val query = TextQuery.fromString("\"type safe\"")
        assertNull(query.scores(listOf(listOf("type", "safe"))).single())
        assertNotNull(query.scores(listOf(listOf("type safe", ""))).single())
    }
}
