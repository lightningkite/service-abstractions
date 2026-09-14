package kspcommon

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import com.lightningkite.services.database.processor.MyProvider
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.walk

/**
 * Runs `database-processor` over one of a module's common source directories and writes the
 * result where this plugin declares it as generated common sources.
 *
 * Two things here are deliberate and neither is obvious.
 *
 * First, KSP is driven through the KSP2 standalone API instead of the Toolchain's built-in KSP
 * integration. The built-in integration wires generated output into per-platform fragments only,
 * and this processor emits `@JvmName`, which the compiler accepts solely in common sources — so
 * per-platform output fails to compile on every native target.
 *
 * Second, the sources are copied into a Gradle-shaped scratch tree before KSP sees them.
 * `CommonSymbolProcessor2` decides whether it is looking at common sources by testing the source
 * paths for `/src/common`, and it derives its own output folder from the KSP output path by
 * locating a `build` ancestor and a `ksp` path segment. Under the Toolchain's layout
 * (`<module>/src`, `<module>/test`) none of that matches, and the processor silently takes its
 * per-platform branch. Staging restores the shape it reads without touching its source.
 *
 * `KSPJvmConfig` — not `KSPCommonConfig` — is also deliberate: under the common config, types
 * coming from libraries silently fail to resolve (`@Serializable` came back null) and the
 * processor then generates wrong output while reporting success.
 */
/**
 * Depth of `build/generated/ksp/common/common<Flavor>/kotlin`, the destination
 * `CommonSymbolProcessor2` computes for itself relative to the project folder it discovers.
 */
private const val OUTPUT_LAYOUT_DEPTH = 6

@TaskAction
@OptIn(ExperimentalPathApi::class)
fun runKspOverCommon(
    @Input sourceDir: Path,
    @Input classpath: Classpath,
    @Input localDependencies: Classpath,
    @Output outputDir: Path,
) {
    // Only Path-typed parameters may be declared, so the flavor rides on the source directory
    // name: the Toolchain's common source root is `src` and its common test root is `test`.
    val flavor = when (val name = sourceDir.name) {
        "src" -> "Main"
        "test" -> "Test"
        else -> error("Expected the module's `src` or `test` directory, got '$name'")
    }
    // The task may declare only one output directory, and nested outputs are rejected, so the
    // scratch root is recovered from the declared output instead of being passed in. It is the
    // ancestor that OUTPUT_LAYOUT hangs off; plugin.yaml builds the same path forwards.
    val workDir = generateSequence(outputDir) { it.parent }.elementAt(OUTPUT_LAYOUT_DEPTH)
    workDir.deleteRecursively()

    // The scratch tree the processor expects to be looking at. `common$flavor` supplies both the
    // `/src/common` marker and the Main/Test discriminator it reads back out of the output path.
    val stagedSources = workDir.resolve("src/common$flavor")
    if (!sourceDir.exists()) {
        // A module may enable this plugin and have no sources of one flavor. Still create the
        // output directory: it is declared as a source root and must exist.
        outputDir.createDirectories()
        return
    }
    stagedSources.createDirectories()
    sourceDir.copyToRecursively(stagedSources, followLinks = false, overwrite = true)

    val kspOut = workDir.resolve("build/ksp/common/common$flavor")
    val config = KSPJvmConfig.Builder().apply {
        javaSourceRoots = emptyList()
        javaOutputDir = kspOut.resolve("java").createDirectories().toFile()
        jvmTarget = "17"
        moduleName = "common${flavor}Ksp"
        sourceRoots = listOf(stagedSources.toFile())
        commonSourceRoots = listOf(stagedSources.toFile())
        libraries = (classpath.resolvedFiles + localDependencies.resolvedFiles).distinct().map { it.toFile() }
        projectBaseDir = workDir.toFile()
        outputBaseDir = kspOut.toFile()
        kotlinOutputDir = kspOut.resolve("kotlin").createDirectories().toFile()
        classOutputDir = kspOut.resolve("classes").createDirectories().toFile()
        // The processor's first generated file is a .txt, so it lands here; this is the path it
        // reverse-engineers its real destination from.
        resourceOutputDir = kspOut.resolve("resources").createDirectories().toFile()
        cachesDir = workDir.resolve("ksp-caches").createDirectories().toFile()
        incremental = false
        languageVersion = "2.2"
        apiVersion = "2.2"
    }.build()

    val logger = object : KSPLogger {
        override fun logging(message: String, symbol: KSNode?) {}
        override fun info(message: String, symbol: KSNode?) {}
        override fun warn(message: String, symbol: KSNode?) = println("[ksp] w: $message")
        override fun error(message: String, symbol: KSNode?) = println("[ksp] e: $message")
        override fun exception(e: Throwable) = e.printStackTrace()
    }

    val exit = KotlinSymbolProcessing(config, listOf(MyProvider()), logger).execute()
    if (exit != KotlinSymbolProcessing.ExitCode.OK) error("KSP over common $flavor sources failed: $exit")

    // Fail loudly rather than compiling against a silently empty source root: if the processor's
    // path arithmetic ever stops landing here, nothing else in the build would notice.
    if (!outputDir.exists()) error(
        "KSP ran but wrote nothing to $outputDir — the processor's output-path convention has changed."
    )
    println("KSP over common $flavor: ${outputDir.walk().count { it.extension == "kt" }} file(s) in $outputDir")
}
