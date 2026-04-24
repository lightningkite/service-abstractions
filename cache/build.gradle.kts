import com.lightningkite.deployhelpers.lkLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidLibrary)
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
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(path = ":basis"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.testing)
                implementation(project(":cache-test"))
            }
        }
        val commonJvmMain by creating {
            dependsOn(commonMain)
            dependencies {
                compileOnly(project(":otel-jvm"))
                compileOnly(libs.openTelemetry.api)
            }
        }
        val nonJvmMain by creating {
            dependsOn(commonMain)
        }
        val nativeMain by getting {
            dependsOn(nonJvmMain)
        }
        val jsMain by getting {
            dependsOn(nonJvmMain)
        }
        val webMain by getting {
            dependsOn(nonJvmMain)
        }
        val jvmMain by getting {
            dependsOn(commonJvmMain)
        }
        val androidMain by getting {
            dependsOn(commonJvmMain)
        }
        val jvmTest by getting {}
    }
}

android {
    namespace = "com.lightningkite.services"
    compileSdk = 36

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

lkLibrary(
    "lightningkite",
    "service-abstractions",
    mavenAutomaticRelease = project.findProperty("mavenAutomaticRelease") as? Boolean ?: false
) {
    description.set("An abstraction for an external cache service.")
}