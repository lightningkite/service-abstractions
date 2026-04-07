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
    api(project(path = ":database"))
    testImplementation(project(path = ":database-test"))
    testImplementation(libs.kotlin.test)
    api(libs.exposed.core)
    api(libs.exposed.javaTime)
    api(libs.exposed.jdbc)
    api(libs.postgresql)
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.testContainers)
    testImplementation(libs.testContainers.postgresql)
    testImplementation(libs.testContainers.junit)
    testImplementation(libs.coroutines.testing)
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
