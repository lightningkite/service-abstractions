import com.lightningkite.deployhelpers.lkLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

// LiteRT-LM's Apple library isn't published anywhere Gradle can fetch it, so apps link it (see README) and this build
// only needs its headers.  Running the Apple tests also needs the library: -PliteRtApple=<folder holding
// CLiteRTLM.xcframework and CLiteRTLM_mac.xcframework>.
val liteRtApple: File? = providers.gradleProperty("liteRtApple").orNull?.let(::File)

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.set(listOf("-Xexpect-actual-classes"))
    }
    explicitApi()
    applyDefaultHierarchyTemplate()
    android {
        namespace = "com.lightningkite.services.ai.embedded"
        compileSdk = 36
        minSdk = 24
        enableCoreLibraryDesugaring = true
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    // litertlm-jvm is compiled for Java 21, so only this target needs it.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    js { browser() }

    // No iosX64: LiteRT-LM has no Intel simulator build.
    fun KotlinNativeTarget.liteRt(testLinkerOpts: (File) -> List<String>) {
        compilations.getByName("main").cinterops.create("CLiteRTLM") {
            includeDirs(file("src/nativeInterop/cinterop/litertlm"))
        }
        liteRtApple?.let { library -> binaries.getTest("DEBUG").linkerOpts(testLinkerOpts(library)) }
    }
    iosArm64().liteRt { listOf("-F", it.resolve("CLiteRTLM.xcframework/ios-arm64").path, "-framework", "CLiteRTLM") }
    iosSimulatorArm64().liteRt { listOf("-F", it.resolve("CLiteRTLM.xcframework/ios-arm64-simulator").path, "-framework", "CLiteRTLM") }
    macosArm64().liteRt {
        val library = it.resolve("CLiteRTLM_mac.xcframework/macos-arm64_x86_64").path
        listOf("-L", library, "-lCLiteRTLM_mac", "-rpath", library)
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                api(project(":ai"))
            }
            kotlin {
                compilerOptions {
                    optIn.add("kotlin.time.ExperimentalTime")
                    optIn.add("kotlin.uuid.ExperimentalUuidApi")
                    freeCompilerArgs.set(listOf("-Xexpect-actual-classes"))
                }
                srcDir(file("build/generated/ksp/common/commonMain/kotlin"))
            }
        }
        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.testing)
            }
            kotlin {
                compilerOptions {
                    optIn.add("kotlin.time.ExperimentalTime")
                    optIn.add("kotlin.uuid.ExperimentalUuidApi")
                    freeCompilerArgs.set(listOf("-Xexpect-actual-classes"))
                }
                srcDir(file("build/generated/ksp/common/commonTest/kotlin"))
            }
        }
        // LiteRT-LM publishes one Kotlin API as two artifacts, so Android and the JVM compile the same source folder.
        val androidMain = getByName("androidMain") {
            kotlin.srcDir("src/liteRtMain/kotlin")
            dependencies {
                implementation(libs.litertlm.android)
            }
        }
        val jvmMain = getByName("jvmMain") {
            kotlin.srcDir("src/liteRtMain/kotlin")
            dependencies {
                implementation(libs.litertlm.jvm)
            }
        }
        val jsMain = getByName("jsMain") {
            dependencies {
                implementation(npm("@huggingface/transformers", "4.3.0"))
            }
        }
        // Real-model tests for the two LiteRT-LM engines that can run on a development machine.
        val liteRtTest = create("liteRtTest") { dependsOn(commonTest) }
        val jvmTest = getByName("jvmTest") { dependsOn(liteRtTest) }
        val appleTest = getByName("appleTest") { dependsOn(liteRtTest) }
    }
}

tasks.named<Test>("jvmTest") {
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
}

// Without the library the Apple test binaries can't link.
tasks.matching { it.name.startsWith("linkDebugTest") || it.name in setOf("iosArm64Test", "iosSimulatorArm64Test", "macosArm64Test") }
    .configureEach { onlyIf("-PliteRtApple gives LiteRT-LM's Apple library") { liteRtApple != null } }

lkLibrary("lightningkite", "service-abstractions") {}

dependencies {
    coreLibraryDesugaring(libs.androidDesugaring)
}

// commonMain compiles the database processor's shared output (written by kspCommonMainKotlinMetadata alone) as plain
// source, so every other KSP run and compile must wait for it.
tasks.matching { (it.name.startsWith("ksp") || it.name.startsWith("compile")) && it.name != "kspCommonMainKotlinMetadata" }
    .configureEach { dependsOn("kspCommonMainKotlinMetadata") }
