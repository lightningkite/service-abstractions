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
    api(project(":ai"))
    // kotlin-test-junit is exposed as api because this module IS a testing utility that
    // compiles @Test methods into its main source set — consumers pick the whole thing up
    // via testImplementation(project(":ai-test")) and inherit JUnit 4 automatically.
    api(libs.kotlin.test.junit)
    api(libs.coroutines.testing)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.set(listOf("-Xcontext-parameters"))
    }
    explicitApi()
}

tasks.withType<JavaCompile>().configureEach { this.targetCompatibility = "17" }

lkLibrary("lightningkite", "service-abstractions") {}
