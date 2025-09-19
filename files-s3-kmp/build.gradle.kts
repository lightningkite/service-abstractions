import com.lightningkite.deployhelpers.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
//    alias(libs.plugins.androidLibrary)
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
//    androidTarget {
//        compilerOptions {
//            jvmTarget.set(JvmTarget.JVM_1_8)
//        }
//    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
//    js(IR) {
//        browser()
//    }
//    iosX64()
//    iosArm64()
//    iosSimulatorArm64()
//    macosX64()
//    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(path = ":files"))
                api(project(path = ":http-client"))
                api(awssdk.services.s3)
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
                implementation(libs.kotlinTest)
                implementation(libs.coroutinesTesting)
                implementation(project(":files-test"))
            }
            kotlin {
                compilerOptions {
                    optIn.add("kotlin.time.ExperimentalTime")
                    optIn.add("kotlin.uuid.ExperimentalUuidApi"); freeCompilerArgs.set(listOf("-Xcontext-parameters"))
                }
                srcDir(file("build/generated/ksp/common/commonTest/kotlin"))
            }
        }
    }
}

lkLibrary("lightningkite", "service-abstractions") {}

//android {
//    namespace = "com.lightningkite.services"
//    compileSdk = 36
//
//    defaultConfig {
//        minSdk = 21
//    }
//    compileOptions {
//        isCoreLibraryDesugaringEnabled = true
//        sourceCompatibility = JavaVersion.VERSION_1_8
//        targetCompatibility = JavaVersion.VERSION_1_8
//    }
//    dependencies {
//        coreLibraryDesugaring(libs.androidDesugaring)
//    }
//}