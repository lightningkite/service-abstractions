package com.lightningkite.services.database.processor

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for the incremental-generation cache's content hash.
 *
 * The bug being guarded against: `checksum()` used to be a plain additive sum of character
 * codes across all dependency files, which is order-insensitive. A same-line-count field
 * reorder in a model (e.g. swapping two declarations) preserves the exact character multiset,
 * so the old checksum was *guaranteed* identical before and after the edit - `processFiles()`
 * then skipped regeneration and left stale, positionally-wrong generated accessors in place
 * with no compile error. A real hash must change on any permutation of the same characters.
 */
class ChecksumTest {
    private fun tempFileWithContent(dir: File, name: String, content: String): File =
        dir.resolve(name).apply { writeText(content) }

    @Test
    fun permutedContentProducesDifferentHash() {
        val dir = kotlin.io.path.createTempDirectory("checksum-test").toFile()
        try {
            // Same characters, same line count, different order - exactly a field reorder.
            val original = tempFileWithContent(dir, "Model.kt", "val name: String\nval nickname: String\n")
            val before = sequenceOf(original).checksum()

            original.writeText("val nickname: String\nval name: String\n")
            val after = sequenceOf(original).checksum()

            assertNotEquals(before, after, "Reordering lines must invalidate the cache")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun identicalContentProducesSameHash() {
        val dir = kotlin.io.path.createTempDirectory("checksum-test").toFile()
        try {
            val file = tempFileWithContent(dir, "Model.kt", "val name: String\nval nickname: String\n")
            val first = sequenceOf(file).checksum()
            val second = sequenceOf(file).checksum()

            assertEquals(first, second, "Hashing the same content twice must be stable")
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Regression test for the lock-file format migration: the stored hash changed from a plain
     * `Int` (`hash.toString()`, e.g. "123456789") to a `String` ("<sha256-hex>:<version>"). A
     * developer's existing working tree has old-format lock files on disk. This must be treated
     * as an ordinary cache miss (regenerate, then overwrite with the new format) - not throw a
     * parse error and not coincidentally compare equal to the new format.
     */
    @Test
    fun legacyIntFormatLockFileIsTreatedAsCacheMiss() {
        val dir = kotlin.io.path.createTempDirectory("lock-migration-test").toFile()
        try {
            val depFile = tempFileWithContent(dir, "Model.kt", "val name: String\n")
            val lockFile = dir.resolve("meta/some.lock").apply { parentFile.mkdirs() }
            // What the old `lockFile.writeText(hash.toString())` on an Int hash left behind.
            lockFile.writeText("123456789")
            val destinationFolder = dir.resolve("out").apply { mkdirs() }

            var regenerated = false
            processFiles(
                version = 0,
                dependencies = sequenceOf(depFile),
                lockFile = lockFile,
                destinationFolder = destinationFolder,
                action = {
                    regenerated = true
                    file("Generated.kt").use { it.write("// generated") }
                }
            )

            assertTrue(regenerated, "An old Int-format lock file must not parse-error or coincidentally match - it must miss the cache and regenerate")
            assertTrue(
                lockFile.readText().contains(":"),
                "After regenerating, the lock file must be rewritten in the new '<hash>:<version>' format"
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
