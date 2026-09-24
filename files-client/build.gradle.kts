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
    explicitApi()
    applyDefaultHierarchyTemplate()
    android {
        namespace = "com.lightningkite.services.files.client"
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
    js(IR) {
        browser()
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.serialization.core)
                api(project(":database-shared"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.testing)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val jvmMain by getting {}
        val jvmTest by getting {}
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
    description.set("A set of models used by the PublicFilesystem service.")
}