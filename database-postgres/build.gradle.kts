import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":database"))
    api(libs.exposed.core)
    api(libs.exposed.javaTime)
    api(libs.exposed.jdbc)
    api(libs.postgresql)

    testImplementation(project(path = ":database-test"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.testContainers)
    testImplementation(libs.testContainers.postgresql)
    testImplementation(libs.testContainers.junit)
    testImplementation(libs.coroutines.testing)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.set(listOf("-Xcontext-parameters"))
    }
    explicitApi()
}

tasks.withType<JavaCompile>().configureEach {
    this.targetCompatibility = "17"
}


lkLibrary(
    "lightningkite",
    "service-abstractions",
    mavenAutomaticRelease = project.findProperty("mavenAutomaticRelease") as? Boolean ?: false
) {
    description.set("A database implementation using Postgresql.")
}
