package com.lightningkite.services.database.processor

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequireGeneratedByMetadataTaskTest {
    private val dir = kotlin.io.path.createTempDirectory("metadata-gate-test").toFile()
    private val model = dir.resolve("Model.kt").apply { writeText("val name: String\n") }
    private val lockFile = dir.resolve("meta/lightningdb-commonMain.lock")

    private fun metadataTaskRuns() = processFiles(
        version = 1,
        dependencies = sequenceOf(model),
        lockFile = lockFile,
        destinationFolder = dir.resolve("out"),
        action = { file("Generated.kt").use { it.write("// generated") } }
    )

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun failsWhenTheMetadataTaskNeverRan() {
        val failure = assertFailsWith<IllegalStateException> {
            requireGeneratedByMetadataTask(version = 1, dependencies = sequenceOf(model), lockFile = lockFile)
        }
        assertTrue("missing" in failure.message!!)
        assertTrue("dependsOn(\"kspCommonMainKotlinMetadata\")" in failure.message!!)
    }

    @Test
    fun failureMessagePointsAtTheBuildSetupDocs() {
        val failure = assertFailsWith<IllegalStateException> {
            requireGeneratedByMetadataTask(version = 1, dependencies = sequenceOf(model), lockFile = lockFile)
        }
        val doc = Regex("""service-abstractions/(docs/[\w-]+\.md)""").find(failure.message!!)!!.groupValues[1]
        val section = Regex(""""([^"]+)" in service-abstractions/""").find(failure.message!!)!!.groupValues[1]
        val docFile = java.io.File("../$doc")
        assertTrue(docFile.exists(), "$doc doesn't exist")
        assertTrue("## $section" in docFile.readText(), "$doc has no \"$section\" section")
    }

    @Test
    fun failsWhenAModelChangedSinceTheMetadataTaskRan() {
        metadataTaskRuns()
        model.writeText("val nickname: String\n")
        val failure = assertFailsWith<IllegalStateException> {
            requireGeneratedByMetadataTask(version = 1, dependencies = sequenceOf(model), lockFile = lockFile)
        }
        assertTrue("out of date" in failure.message!!)
    }

    @Test
    fun passesAfterTheMetadataTaskRan() {
        metadataTaskRuns()
        requireGeneratedByMetadataTask(version = 1, dependencies = sequenceOf(model), lockFile = lockFile)
    }

    @Test
    fun passesWhenTheTaskSeesNoModels() {
        requireGeneratedByMetadataTask(version = 1, dependencies = emptySequence(), lockFile = lockFile)
    }
}
