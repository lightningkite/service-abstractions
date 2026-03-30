import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":basis"))
    api(project(path = ":speech"))

    // FreeTTS for text-to-speech
    implementation(libs.freetts)

    // Vosk for speech-to-text
    // Note: 0.3.38 is the last stable version before API changes that broke JNA bindings
    // See: https://github.com/alphacep/vosk-api/issues/1235
    implementation(libs.vosk)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
    testImplementation(project(path = ":test"))
    testImplementation(project(path = ":speech-test"))
    testImplementation(libs.logBackClassic)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi"); freeCompilerArgs.set(listOf("-Xcontext-parameters"))
    }
    explicitApi()
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}

tasks.withType<JavaCompile>().configureEach {
    this.targetCompatibility = "17"
}

lkLibrary("lightningkite", "service-abstractions") {}
