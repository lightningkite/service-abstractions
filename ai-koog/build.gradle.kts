import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":basis"))
    api(project(path = ":database-shared"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)

    // Koog agents framework - includes all LLM clients
    api(libs.koog.agents)
    api(libs.koog.vector.storage)

    // Ktor client for Ollama management REST API
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.contentNegotiation)
    implementation(libs.ktor.json)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
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
    description.set("A set of tools for handling LLM integrations.")
}
