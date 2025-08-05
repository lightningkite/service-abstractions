import com.lightningkite.deployhelpers.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
    alias(libs.plugins.androidLibrary)
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
    androidTarget {
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
    macosX64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinXJson)
                api(libs.kotlinXDatetime)
                api(project(":basis"))
                api(libs.coroutinesCore)
                api(libs.kotlinTest)
                api(libs.coroutinesTesting)
            }
            kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
                srcDir(file("build/generated/ksp/common/commonMain/kotlin"))
            }
        }
        val commonTest by getting {
            dependencies {
            }
            kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
                srcDir(file("build/generated/ksp/common/commonTest/kotlin"))
            }
        }
        val androidMain by getting {
            dependencies {
                api(libs.kotlinTestJunit)
            }
        }
        val jsMain by getting {
            dependencies {
                api(libs.kotlinTestJs)
            }
        }
        val jvmMain by getting {
            dependencies {
                api(libs.kotlinTestJunit)
            }
        }
        val jvmTest by getting {
        }
    }
}

lkLibrary("lightningkite", "service-abstractions") {}

android {
    namespace = "com.lightningkite.services"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    dependencies {
        coreLibraryDesugaring(libs.androidDesugaring)
    }
}