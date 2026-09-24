import com.lightningkite.deployhelpers.lkLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()
    android {
        namespace = "com.lightningkite.services.http.client"
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
                api(project(":basis"))
                api(libs.ktor.client.cio)
                api(libs.ktor.client.websockets)
                api(libs.ktor.contentNegotiation)
                api(libs.ktor.json)
                api(libs.ktor.client.auth)
            }
        }
        val commonTest by getting {
            dependencies {
                api(libs.kotlin.test)
                api(libs.coroutines.testing)
            }
        }
        val androidMain by getting {}
        val jsMain by getting {}
        val jvmMain by getting {
            dependencies {
                // OkHttp engine: gives the shared JVM client HTTP/2 (multiplexing), which CIO lacks.
                // High-fanout services (FCM push) and connection reuse across all services benefit.
                implementation(libs.ktor.client.okhttp)
                compileOnly(libs.openTelemetry.api)
                compileOnly(libs.openTelemetry.instrumentation.ktor)
                implementation(libs.crac)
            }
        }
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
    description.set("A common source for an HTTP Client using Ktor's Client.")
}