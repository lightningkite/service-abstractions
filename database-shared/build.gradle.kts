import com.lightningkite.deployhelpers.lkLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
    id("org.jetbrains.kotlinx.atomicfu") version "0.32.1"
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
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
                api(project(path = ":data-shared"))
                implementation(libs.kotlinx.serialization.json)
            }
            kotlin {
                srcDir(file("build/generated/ksp/common/commonMain/kotlin"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(project(":test"))
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.testing)
            }
            kotlin {
                srcDir(file("build/generated/ksp/common/commonTest/kotlin"))
            }
        }
        val nonJvmMain by creating {
            dependsOn(commonMain)
        }
        val jvmCommonMain by creating {
            dependsOn(commonMain)
        }
        val androidMain by getting {
            dependsOn(jvmCommonMain)
        }
        val jvmMain by getting {
            dependsOn(jvmCommonMain)
        }
        val jvmTest by getting {}
        val jsMain by getting { dependsOn(nonJvmMain) }
        val iosX64Main by getting { dependsOn(nonJvmMain) }
        val iosArm64Main by getting { dependsOn(nonJvmMain) }
        val iosSimulatorArm64Main by getting { dependsOn(nonJvmMain) }
        val macosArm64Main by getting { dependsOn(nonJvmMain) }
    }
}

dependencies {
    configurations.filter { it.name.startsWith("ksp") }.forEach {
        add(it.name, project(":database-processor"))
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
    description.set("A set of classes used in querying and modifying databases.")
}