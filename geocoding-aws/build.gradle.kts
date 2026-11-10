plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":geocoding"))
    api(project(path = ":aws-client"))

    // Exclude the SDK's bundled default HTTP clients; the shared client pair comes from :aws-client.
    api(libs.aws.geoPlaces) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
    testImplementation(libs.aws.signin)
    testImplementation(project(path = ":test"))
    testImplementation(project(path = ":geocoding-test"))
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
    explicitApi()
}

tasks.withType<JavaCompile>().configureEach {
    this.targetCompatibility = "17"
}

mavenPublishing {
    pom { description.set("A geocoding implementation using Amazon Location Service.") }
}
