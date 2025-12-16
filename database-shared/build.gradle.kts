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
    id("org.jetbrains.kotlinx.atomicfu") version "0.29.0"
    // Removed custom Gradle plugin - using kotlinCompilerPluginClasspath directly
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi"); freeCompilerArgs.set(listOf("-Xcontext-parameters"))
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
    macosX64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(path = ":data"))
            }
            kotlin {
                compilerOptions {
                    optIn.add("kotlin.time.ExperimentalTime")
                    optIn.add("kotlin.uuid.ExperimentalUuidApi"); freeCompilerArgs.set(listOf("-Xcontext-parameters"))
                }
                srcDir(file("build/generated/ksp/common/commonMain/kotlin"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(project(":test"))
                implementation(libs.kotlinTest)
                implementation(libs.coroutinesTesting)
            }
            kotlin {
                compilerOptions {
                    optIn.add("kotlin.time.ExperimentalTime")
                    optIn.add("kotlin.uuid.ExperimentalUuidApi"); freeCompilerArgs.set(listOf("-Xcontext-parameters"))
                }
                srcDir(file("build/generated/ksp/common/commonMain/kotlin"))
                srcDir(file("build/generated/ksp/common/commonTest/kotlin"))
            }
        }
        val nonJvmMain by creating {
            dependsOn(commonMain)
            dependencies {
            }
        }
        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
            }
        }
        val androidMain by getting {
            dependsOn(jvmCommonMain)
        }
        val jvmMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
            }
        }
        val jvmTest by getting {
        }
        val jsMain by getting { dependsOn(nonJvmMain) }
        val iosX64Main by getting { dependsOn(nonJvmMain) }
        val iosArm64Main by getting { dependsOn(nonJvmMain) }
        val iosSimulatorArm64Main by getting { dependsOn(nonJvmMain) }
        val macosX64Main by getting { dependsOn(nonJvmMain) }
        val macosArm64Main by getting { dependsOn(nonJvmMain) }
    }
}

dependencies {
    configurations.filter { it.name.startsWith("ksp") }.forEach {
        add(it.name, project(":database-processor"))
    }
    // Add compiler plugin directly to classpath
    kotlinCompilerPluginClasspath(project(":database-compiler-plugin"))
}

lkLibrary("lightningkite", "service-abstractions") {}

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