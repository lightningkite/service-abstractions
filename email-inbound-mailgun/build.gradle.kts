plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":email-inbound"))
    api(project(path = ":webhook-subservice"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
    testImplementation(project(":test"))
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
    explicitApi()
}

tasks.withType<JavaCompile>().configureEach {
    this.targetCompatibility = "17"
}

mavenPublishing {
    pom { description.set("An inbound email implementation using the mailgun api.") }
}
