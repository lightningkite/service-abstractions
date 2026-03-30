
plugins {
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.androidApp) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.graalVmNative) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.versionCatalogUpdate)
    id("org.jetbrains.kotlinx.atomicfu") version "0.29.0"
}

buildscript {
    repositories {
        mavenLocal()
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
    }
    dependencies {
        classpath(libs.lkGradleHelpers)
        classpath(libs.proguard)
    }
}

allprojects {
    group = "com.lightningkite.services"

    repositories {
        mavenLocal()
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
        google()
        mavenCentral()
    }
}
