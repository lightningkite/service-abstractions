import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlinJvm)
    id("java-gradle-plugin")
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    implementation(project(":database-compiler-plugin"))
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api")

    testImplementation(libs.kotlinTest)
}

gradlePlugin {
    plugins {
        create("databaseDefaultsPlugin") {
            id = "com.lightningkite.serviceabstractions.database-defaults"
            displayName = "Database Defaults Compiler Plugin"
            description = "Kotlin Compiler Plugin for populating default values in SerializableProperty instances"
            implementationClass = "com.lightningkite.services.database.gradle.DatabaseDefaultsGradlePlugin"
        }
    }
}

kotlin {
    explicitApi()
}

lkLibrary("lightningkite", "service-abstractions") {}
