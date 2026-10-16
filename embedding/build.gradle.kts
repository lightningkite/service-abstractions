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
    }
    explicitApi()
    applyDefaultHierarchyTemplate()
    android {
        namespace = "com.lightningkite.services.embedding"
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
                api(project(":basis"))
                api(project(":database-shared"))
            }
            kotlin {
                compilerOptions {
                    optIn.add("kotlin.time.ExperimentalTime")
                    optIn.add("kotlin.uuid.ExperimentalUuidApi")
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
                }
                srcDir(file("build/generated/ksp/common/commonTest/kotlin"))
            }
        }
        val nonJvmMain = create("nonJvmMain") {
            dependsOn(commonMain)
        }
        val jvmCommonMain = create("jvmCommonMain") {
            dependsOn(commonMain)
        }
        val androidMain = getByName("androidMain") { dependsOn(jvmCommonMain) }
        val jvmMain = getByName("jvmMain") { dependsOn(jvmCommonMain) }
        val jsMain = getByName("jsMain") { dependsOn(nonJvmMain) }
        val iosX64Main = getByName("iosX64Main") { dependsOn(nonJvmMain) }
        val iosArm64Main = getByName("iosArm64Main") { dependsOn(nonJvmMain) }
        val iosSimulatorArm64Main = getByName("iosSimulatorArm64Main") { dependsOn(nonJvmMain) }
        val macosArm64Main = getByName("macosArm64Main") { dependsOn(nonJvmMain) }
    }
}

lkLibrary("lightningkite", "service-abstractions") {}

dependencies {
    coreLibraryDesugaring(libs.androidDesugaring)
}
