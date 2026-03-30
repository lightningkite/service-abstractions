import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":basis"))
    api(project(path = ":pubsub"))
    api(libs.dynamodb)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.reactive)
    implementation(libs.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.crac)
    implementation(project(":aws-client"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
    testImplementation(project(":test"))
    testImplementation(project(":pubsub-test"))
    testImplementation(project(":cache-dynamodb"))  // For embeddedDynamo()
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

lkLibrary("lightningkite", "service-abstractions") {}
