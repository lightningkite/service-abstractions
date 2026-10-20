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
        namespace = "com.lightningkite.services.database.native"
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
        commonMain {
            dependencies {
                api(project(path = ":database"))
                implementation(project(path = ":database-mapformat"))
                implementation(libs.androidx.sqlite)
                implementation(libs.androidx.sqlite.async)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest {
            dependencies {
                implementation(project(":database-test"))
                implementation(project(":test"))
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.testing)
            }
            kotlin.srcDir(file("build/generated/ksp/common/commonTest/kotlin"))
        }
        val bundledMain = create("bundledMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.androidx.sqlite.bundled)
            }
        }
        androidMain { dependsOn(bundledMain) }
        jvmMain { dependsOn(bundledMain) }
        appleMain { dependsOn(bundledMain) }
        jsMain {
            dependencies {
                implementation(libs.androidx.sqlite.web)
                implementation(npm("@sqlite.org/sqlite-wasm", libs.versions.sqliteWasm.get()))
            }
        }
    }
}

dependencies {
    configurations.filter { it.name.startsWith("ksp") && it.name != "ksp" }.forEach {
        add(it.name, project(":database-processor"))
    }
    coreLibraryDesugaring(libs.androidDesugaring)
}

lkLibrary(
    "lightningkite",
    "service-abstractions",
    mavenAutomaticRelease = project.findProperty("mavenAutomaticRelease") as? Boolean ?: false
) {
    description.set("A local, persistent database on SQLite for Android, iOS, macOS, desktop JVM, and the web.")
}
