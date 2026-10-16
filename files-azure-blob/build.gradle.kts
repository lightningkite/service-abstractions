import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":files"))
    api(libs.azure.storage.blob)
    api(libs.azure.identity)
    testImplementation(project(path = ":files-test"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
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

lkLibrary(
    "lightningkite",
    "service-abstractions",
    mavenAutomaticRelease = project.findProperty("mavenAutomaticRelease") as? Boolean ?: false
) {
    description.set("A PublicFileSystem implementation using Azure Blob Storage.")
}

tasks.withType<Test>().configureEach {
    // Created by scripts/azurite.sh, so the tests trust Azurite's self-signed HTTPS certificate.
    val trustStore = rootProject.file("local/azurite/truststore.pfx")
    if (trustStore.exists()) {
        systemProperty("javax.net.ssl.trustStore", trustStore.absolutePath)
        systemProperty("javax.net.ssl.trustStorePassword", "azurite")
    }
}
