plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":email"))
    implementation(project(":http-client"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(project(":test"))
}

kotlin {
    explicitApi()
}

tasks.withType<JavaCompile>().configureEach {
    this.targetCompatibility = "17"
}

mavenPublishing {
    pom { description.set("An email implementation using the mailgun api.") }
}
