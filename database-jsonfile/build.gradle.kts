plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":database"))
    testImplementation(project(path = ":database-test"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
}

kotlin {
    explicitApi()
}

tasks.withType<JavaCompile>().configureEach {
    this.targetCompatibility = "17"
}

mavenPublishing {
    pom { description.set("A database implementation using local ram and permanant storage in json files.") }
}
