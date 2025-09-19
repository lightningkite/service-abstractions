import com.lightningkite.deployhelpers.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi"); freeCompilerArgs.set(listOf("-Xcontext-parameters"))
    }
    explicitApi()
    applyDefaultHierarchyTemplate()

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) }
    }
    // Other targets can be enabled in the future when AWS Kotlin SDK supports them fully
    // js(IR) { browser() }
    // iosX64(); iosArm64(); iosSimulatorArm64(); macosX64(); macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":cache"))
                api(awssdk.services.dynamodb)
                implementation(libs.kotlinXJson)
                implementation(libs.coroutinesCore)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlinTest)
            }
        }
    }
}

lkLibrary("lightningkite", "service-abstractions") {}
