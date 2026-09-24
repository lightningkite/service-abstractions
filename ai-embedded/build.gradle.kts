import com.lightningkite.deployhelpers.lkLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
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

    js(IR) {
        browser()
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
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
        val commonTest by getting {
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
        val androidMain by getting {
            dependencies {
                implementation(libs.litertlm.android)
            }
        }
        val jsMain by getting {
            dependencies {
                implementation(npm("@huggingface/transformers", "4.3.0"))
            }
        }
    }
}

lkLibrary("lightningkite", "service-abstractions") {}

dependencies {
    coreLibraryDesugaring(libs.androidDesugaring)
}
