import com.lightningkite.deployhelpers.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
    id("org.jetbrains.kotlinx.atomicfu") version "0.29.0"
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.set(listOf("-Xcontext-parameters"))
    }
    explicitApi()
    applyDefaultHierarchyTemplate()

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
                api(project(path = ":database"))
                api(libs.atomicfu)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(project(":test"))
                implementation(libs.kotlinTest)
                implementation(libs.coroutinesTesting)
            }
        }
    }
}

dependencies {
    configurations.filter { it.name.startsWith("ksp") }.forEach {
        add(it.name, project(":database-processor"))
    }
}

lkLibrary("lightningkite", "service-abstractions") {}
