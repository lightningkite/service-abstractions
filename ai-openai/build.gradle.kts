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
    explicitApi()
    applyDefaultHierarchyTemplate()
    android {
        namespace = "com.lightningkite.services.ai.openai"
        compileSdk = 37
        minSdk = 21
        enableCoreLibraryDesugaring = true
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
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
                api(project(":ai"))
                api(project(":http-client"))
                implementation(libs.kotlinx.io)
            }
        }
        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.testing)
            }
        }
        val jvmCommonMain = create("jvmCommonMain") {
            dependsOn(commonMain)
        }
        val androidMain = getByName("androidMain") { dependsOn(jvmCommonMain) }
        val jvmMain = getByName("jvmMain") { dependsOn(jvmCommonMain) }
        // :ai-test is JVM-only, so the live and integration suites live in jvmTest.
        val jvmTest = getByName("jvmTest") {
            dependencies {
                implementation(project(":ai-test"))
                implementation(project(":test"))
            }
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.androidDesugaring)
}
