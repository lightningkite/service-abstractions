import com.lightningkite.deployhelpers.lkLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
    applyDefaultHierarchyTemplate()
    android {
        namespace = "com.lightningkite.services.test"
        compileSdk = 36
        minSdk = 21
        enableCoreLibraryDesugaring = true
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
    js { browser() }

    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.datetime)
                api(project(path = ":basis"))
                api(libs.coroutines.core)
                api(libs.kotlin.test)
                api(libs.coroutines.testing)
            }
        }
        val commonTest = getByName("commonTest") {}
        val androidMain = getByName("androidMain") {
            dependencies {
                api(libs.slf4j.simple)
                api(libs.kotlin.test.junit)
            }
        }
        val jsMain = getByName("jsMain") {
            dependencies {
                api(libs.kotlin.test.js)
            }
        }
        val jvmMain = getByName("jvmMain") {
            dependencies {
                api(libs.slf4j.simple)
                api(libs.kotlin.test.junit)
            }
        }
        val jvmTest = getByName("jvmTest") {}
    }
}

dependencies {
    coreLibraryDesugaring(libs.androidDesugaring)
}

lkLibrary(
    "lightningkite",
    "service-abstractions",
    mavenAutomaticRelease = project.findProperty("mavenAutomaticRelease") as? Boolean ?: false
) {
    description.set("A set of tools to help with unit tests.")
}