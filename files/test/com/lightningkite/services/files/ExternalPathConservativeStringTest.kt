package com.lightningkite.services.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ExternalPath.toString] and [ExternalPath.fromConservativeString] are the single place where a
 * path becomes text and back, so every reference we hand out or store depends on the two being
 * exact inverses, and on the reader refusing anything the writer would never have produced.
 */
class ExternalPathConservativeStringTest {

    private val awkwardPaths = listOf(
        ExternalPath(emptyList()),
        ExternalPath("test.txt"),
        ExternalPath("folder", "test.txt"),
        ExternalPath("hello world.txt"),
        ExternalPath("under_score.txt"),
        ExternalPath("a+b.txt"),
        ExternalPath("file%20name.txt"),
        ExternalPath("100%done.txt"),
        ExternalPath("café.txt"),
        ExternalPath("emoji 😀.txt"),
        ExternalPath("a=b&c?d#e.txt"),
        ExternalPath("quote\"and'apostrophe.txt"),
        // Segments are literal, so all of these are legitimate names rather than navigation.
        ExternalPath(".."),
        ExternalPath("."),
        ExternalPath("..", "still-a-name"),
        ExternalPath("a/b"),
        ExternalPath("a\\b"),
        ExternalPath("newline\nand\ttab"),
    )

    @Test
    fun roundTrips() {
        for (path in awkwardPaths) {
            val text = path.toString()
            assertEquals(path, ExternalPath.fromConservativeString(text), "round trip of $text")
        }
    }

    @Test
    fun outputIsAlwaysUrlSafe() {
        val allowed = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '.', '_', '/')
        for (path in awkwardPaths) {
            val text = path.toString()
            assertTrue(text.all { it in allowed }, "'$text' contains characters needing further escaping")
        }
    }

    /**
     * A separator or a `..` segment must not survive rendering as itself, or a reference could be
     * read as a traversal by whatever consumes the string.
     */
    @Test
    fun traversalNeverAppearsInOutput() {
        // Escaping the leading dot is enough: the segment is no longer all dots.
        assertEquals("_002e.", ExternalPath("..").toString())
        assertEquals("_002e", ExternalPath(".").toString())
        assertEquals("a_002fb", ExternalPath("a/b").toString())
        assertEquals("a_005cb", ExternalPath("a\\b").toString())
        for (path in awkwardPaths) {
            assertTrue(path.toString().split('/').none { it == "." || it == ".." }, "$path rendered as traversal")
        }
    }

    @Test
    fun escapeCharacterItselfIsEscaped() {
        assertEquals("a_005fb", ExternalPath("a_b").toString())
        assertEquals(ExternalPath("a_0020b"), ExternalPath.fromConservativeString("a_005f0020b"))
    }

    @Test
    fun rejectsUnescapedCharacters() {
        for (bad in listOf("hello world", "a+b", "100%done", "café", "a=b", "a?b", "a\\b")) {
            assertFailsWith<IllegalArgumentException>("should have rejected '$bad'") {
                ExternalPath.fromConservativeString(bad)
            }
        }
    }

    /** A bare traversal segment is not something [ExternalPath.toString] can produce. */
    @Test
    fun rejectsUnescapedTraversal() {
        for (bad in listOf("..", ".", "../a", "a/..", "a/../b", "...")) {
            assertFailsWith<IllegalArgumentException>("should have rejected '$bad'") {
                ExternalPath.fromConservativeString(bad)
            }
        }
    }

    @Test
    fun rejectsMalformedEscapes() {
        for (bad in listOf("a_", "a_00", "a_002", "a_00zz", "a_+123", "a_ 123", "a_002E", "a_-123")) {
            assertFailsWith<IllegalArgumentException>("should have rejected '$bad'") {
                ExternalPath.fromConservativeString(bad)
            }
        }
    }

    /**
     * Uppercase hex and other alternative spellings must be rejected rather than accepted, so a
     * given path has exactly one canonical string - no second spelling to slip past a comparison.
     */
    @Test
    fun encodingIsOneToOne() {
        val renderings = awkwardPaths.map { it.toString() }
        assertEquals(renderings.size, renderings.distinct().size, "two paths shared a rendering")
    }

    @Test
    fun emptyStringIsRoot() {
        assertEquals(ExternalPath(emptyList()), ExternalPath.fromConservativeString(""))
    }

    /**
     * The one path that does not survive the round trip: a single empty segment renders the same as
     * the root, and the root is the reading worth having. `then` drops empty segments, so such a
     * path only exists if it is constructed directly.
     */
    @Test
    fun singleEmptySegmentDegradesToRoot() {
        assertEquals("", ExternalPath("").toString())
        assertEquals(ExternalPath(emptyList()), ExternalPath.fromConservativeString(ExternalPath("").toString()))
    }
}
