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
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
        freeCompilerArgs.set(listOf("-Xcontext-parameters"))
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
                api(project(":ai"))
                api(project(":http-client"))
                implementation(libs.kotlincrypto.macs.hmac.sha2)
                implementation(libs.kotlincrypto.hash.sha2)
            }
            kotlin {
                compilerOptions {
                    optIn.add("kotlin.time.ExperimentalTime")
                    optIn.add("kotlin.uuid.ExperimentalUuidApi")
                    optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
                    freeCompilerArgs.set(listOf("-Xcontext-parameters"))
                }
                srcDir(file("build/generated/ksp/common/commonMain/kotlin"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.testing)
            }
            kotlin {
                compilerOptions {
                    optIn.add("kotlin.time.ExperimentalTime")
                    optIn.add("kotlin.uuid.ExperimentalUuidApi")
                    optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
                    freeCompilerArgs.set(listOf("-Xcontext-parameters"))
                }
                srcDir(file("build/generated/ksp/common/commonTest/kotlin"))
            }
        }
        val nonJvmMain by creating {
            dependsOn(commonMain)
        }
        val jvmCommonMain by creating {
            dependsOn(commonMain)
        }
        val androidMain by getting { dependsOn(jvmCommonMain) }
        val jvmMain by getting { dependsOn(jvmCommonMain) }
        val jsMain by getting { dependsOn(nonJvmMain) }
        val iosX64Main by getting { dependsOn(nonJvmMain) }
        val iosArm64Main by getting { dependsOn(nonJvmMain) }
        val iosSimulatorArm64Main by getting { dependsOn(nonJvmMain) }
        val macosX64Main by getting { dependsOn(nonJvmMain) }
        val macosArm64Main by getting { dependsOn(nonJvmMain) }

        // :ai-test is JVM-only; wiring it into jvmTest (not commonTest) keeps iOS/JS test
        // compilation green while still letting the integration suites extend its JUnit
        // test classes.
        val jvmTest by getting {
            dependencies {
                implementation(project(":ai-test"))
                implementation(project(":test"))
            }
        }
    }
}

lkLibrary("lightningkite", "service-abstractions") {}

android {
    namespace = "com.lightningkite.services.ai.bedrock"
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
