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
    id("org.jetbrains.kotlinx.atomicfu") version "0.33.0"
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
    explicitApi()
    applyDefaultHierarchyTemplate()
    android {
        namespace = "com.lightningkite.services.database"
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
                api(project(path = ":basis"))
                api(project(path = ":database-shared"))
                api(libs.atomicfu)
            }
            kotlin {
                srcDir(file("build/generated/ksp/common/commonMain/kotlin"))
            }
        }
        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(project(":test"))
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.testing)
            }
            kotlin {
                compilerOptions {
                    optIn.add("kotlin.uuid.ExperimentalUuidApi")
                }
                srcDir(file("build/generated/ksp/common/commonTest/kotlin"))
            }
        }
        val nonJvmMain = create("nonJvmMain") {
            dependsOn(commonMain)
        }
        val nativeMain = getByName("nativeMain") {
            dependsOn(nonJvmMain)
        }
        val jsMain = getByName("jsMain") {
            dependsOn(nonJvmMain)
        }
        val androidMain = getByName("androidMain") {
            dependsOn(nonJvmMain)
        }
        val jvmMain = getByName("jvmMain") {
            dependencies {
                api(libs.openTelemetry.api)
                api(libs.openTelemetry.kotlin)
            }
        }
    }
}

dependencies {
    configurations.filter { it.name.startsWith("ksp") && it.name != "ksp" }.forEach {
        add(it.name, project(":database-processor"))
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
    description.set("An abstraction for an external database service.")
}