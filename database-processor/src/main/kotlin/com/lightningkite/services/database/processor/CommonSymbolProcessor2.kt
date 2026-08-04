package com.lightningkite.services.database.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.Writer

abstract class CommonSymbolProcessor2(
    private val myCodeGenerator: CodeGenerator,
    val myId: String,
    val version: Int = 0,
) : SymbolProcessor {
    lateinit var log: Appendable
    abstract fun process2(resolver: Resolver, files: Set<KSFile>)
    abstract fun interestedIn(resolver: Resolver): Set<KSFile>

    private lateinit var fileCreator: (dependencies: Dependencies, packageName: String, fileName: String, extensionName: String) -> Writer

    private var invoked = false
    final override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return listOf()
        invoked = true

        val log = myCodeGenerator.createNewFile(
            Dependencies.ALL_FILES,
            fileName = "$myId-log",
            extensionName = "txt",
            packageName = "com.lightningkite.lightningserver"
        ).writer()
        this.log = log

        myCodeGenerator.createNewFile(
            Dependencies.ALL_FILES,
            fileName = "$myId",
            extensionName = "txt",
            packageName = "com.lightningkite.lightningserver"
        ).writer().use {
            it.appendLine("All reported files below")
            resolver.getAllFiles().forEach { f -> it.appendLine(f.filePath) }
        }
        val outSample = myCodeGenerator.generatedFile.first().absoluteFile
        val projectFolder = generateSequence(outSample) { it.parentFile!! }
            .first { it.name == "build" }
            .parentFile!!
        val common = resolver.getAllFiles().any { it.filePath.contains("/src/common", true) }
        val flavor = outSample.path.split(File.separatorChar)
            .dropWhile { it != "ksp" }
            .drop(2)
            .first()
            .let {
                if (it.contains("test", true)) "Test"
                else "Main"
            }

        val interestedIn = interestedIn(object : Resolver by resolver {
            override fun getAllFiles(): Sequence<KSFile> {
                return resolver.getAllFiles().filter {
                    !common || it.filePath.contains("/src/common")
                }
            }
        })

        try {
            val metaFolder = projectFolder.resolve("build/lightningserver/cache")
            val outFolder = projectFolder.resolve("build/generated/ksp/common/common$flavor/kotlin")
            metaFolder.mkdirs()
            outFolder.mkdirs()

            if (common) {
                processFiles(
                    version = version,
                    dependencies = interestedIn.asSequence().map { it.filePath.let(::File) },
                    // Keyed by flavor as well as processor: main and test generate from different
                    // sources into different folders, so sharing one hash file made each run see the
                    // other flavor's hash, miss the cache, and regenerate on every build.
                    lockFile = metaFolder.resolve("$myId-common$flavor.lock"),
                    destinationFolder = outFolder.resolve(myId).also { it.mkdirs() },
                    action = {
                        fileCreator = label@{ _, packageName, fileName, extensionName ->
                            val packagePath =
                                packageName.split('.').filter { it.isNotBlank() }.joinToString("") { "$it/" }
                            this.file("${packagePath}$fileName.$extensionName")
                        }
                        process2(resolver, interestedIn)
                    }
                )
            } else {
                myCodeGenerator.createNewFile(
                    Dependencies.ALL_FILES,
                    fileName = "$myId.analyzed",
                    extensionName = "txt",
                    packageName = "com.lightningkite.lightningserver"
                ).writer().use {
                    it.appendLine("Analyzed files below")
                    interestedIn.forEach { f -> it.appendLine(f.filePath) }
                }
                fileCreator = label@{ dependencies, packageName, fileName, extensionName ->
                    myCodeGenerator.createNewFile(
                        dependencies,
                        packageName,
                        fileName,
                        extensionName
                    ).bufferedWriter()
                }
                process2(resolver, interestedIn)
            }
        } finally {
            log.close()
        }

        return listOf()
    }

    fun createNewFile(
        dependencies: Dependencies,
        packageName: String,
        fileName: String,
        extensionName: String = "kt",
    ): Writer {
        return fileCreator(dependencies, packageName, fileName, extensionName)
    }
}


fun Sequence<File>.checksum() = sumOf { it.readText().sumOf { it.code } }
interface FileGenerator {
    fun file(name: String): Writer
}

fun processFiles(
    version: Int,
    dependencies: Sequence<File>,
    lockFile: File,
    destinationFolder: File,
    action: FileGenerator.() -> Unit,
) {
    lockFile.parentFile.mkdirs()
    File(lockFile.absolutePath + ".dependencies").writeText(dependencies.joinToString("\n"))
    val hash = dependencies.checksum() + version

    // Every KSP task of every target runs this processor, so the lock is what makes exactly one of
    // them generate while the rest fall through to the up-to-date check below. Readers are safe
    // because the live output folder is never emptied and files are replaced atomically — see
    // [syncInto]. The wait is generous because a large project can queue up a lot of tasks here.
    val runningFile = File(lockFile.absolutePath + ".running")
    var count = 0
    while (!runningFile.createNewFile() && count++ < 600) {
        Thread.sleep(100)
    }
    if (count >= 600) throw IllegalStateException(
        "Timed out waiting for $runningFile. If no build is running, delete it."
    )
    try {
        val hashFromFile = lockFile.takeIf { it.exists() }?.readText()?.toIntOrNull()
        val outputsFile = File(lockFile.absolutePath + ".outputs")
        val previousOutputs = outputsFile.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }.orEmpty()
        // Up to date when the inputs are unchanged and everything previously produced is still there.
        // The recorded list is what makes this work for a module that legitimately generates nothing:
        // asking the folder whether it holds files would say "no" forever and regenerate every run.
        if (hash == hashFromFile && previousOutputs.all { destinationFolder.resolve(it).exists() }) return

        // Generate to the side, then move only what actually changed into place. Rewriting the
        // destination directly would briefly empty a source root the IDE is indexing, breaking
        // resolution in the common source set every time this runs.
        println("Regenerating $destinationFolder (hash $hashFromFile -> $hash)")
        // Staged beside the lock file rather than beside the destination: the destination lives
        // inside a Kotlin source root, and a folder of .kt files there would be compiled too,
        // producing duplicate declarations. The lock file name already distinguishes the flavor.
        val staging = lockFile.parentFile.resolve(lockFile.nameWithoutExtension + ".staging")
        staging.deleteRecursively()
        staging.mkdirs()
        val written: Set<String>
        try {
            action(object : FileGenerator {
                override fun file(name: String): Writer {
                    return staging.resolve(name).also {
                        it.parentFile.mkdirs()
                    }.bufferedWriter()
                }
            })
            written = staging.syncInto(destinationFolder)
        } finally {
            staging.deleteRecursively()
        }
        // Recorded only once the output is actually valid. Writing it up front meant a failed run
        // left an empty folder marked current, so every later build skipped regenerating it.
        outputsFile.writeText(written.joinToString("\n"))
        lockFile.writeText(hash.toString())
    } finally {
        runningFile.delete()
    }
}

/**
 * Makes [destination] match this folder, rewriting only files whose contents differ and removing
 * only files that are no longer generated. Untouched files keep their identity, so anything
 * watching the folder — the IDE especially — sees a stable set of sources.
 *
 * Returns the destination-relative paths that now make up the generated output.
 */
private fun File.syncInto(destination: File): Set<String> {
    destination.mkdirs()
    val generated = walkTopDown().filter { it.isFile }.map { it.relativeTo(this).path }.toSet()
    for (relative in generated) {
        val from = resolve(relative)
        val to = destination.resolve(relative)
        if (to.exists() && to.readText() == from.readText()) continue
        to.parentFile.mkdirs()
        // Land the content on a temp file beside the target, then rename it into place. Rename is
        // atomic within a filesystem, so a compiler or IDE reading this source root while another
        // target's KSP task regenerates sees either the old file or the new one, never a partial
        // one. The temp name is not a .kt file, so it is never itself compiled.
        val temp = File(to.absolutePath + ".tmp")
        from.copyTo(temp, overwrite = true)
        Files.move(temp.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
    destination.walkTopDown()
        .filter { it.isFile && it.relativeTo(destination).path !in generated }
        .toList()
        .forEach { it.delete() }
    return generated
}
