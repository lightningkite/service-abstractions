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
    }
    explicitApi()
    applyDefaultHierarchyTemplate()
    android {
        namespace = "com.lightningkite.services.cache"
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
            }
        }
        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.testing)
                implementation(project(":cache-test"))
            }
        }
        val commonJvmMain = create("commonJvmMain") {
            dependsOn(commonMain)
            dependencies {
                compileOnly(libs.openTelemetry.api)
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
        val webMain = getByName("webMain") {
            dependsOn(nonJvmMain)
        }
        val jvmMain = getByName("jvmMain") {
            dependsOn(commonJvmMain)
        }
        val androidMain = getByName("androidMain") {
            dependsOn(commonJvmMain)
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
    description.set("An abstraction for an external cache service.")
}