import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":basis"))
    // AWS service SDKs each transitively bundle both default HTTP clients (netty-nio + apache).
    // Exclude them so we control exactly which client is on the classpath, then add a single
    // shared pair below. This replaces aws-crt-client, whose native runtime (aws-crt) added ~19MB.
    fun ModuleDependency.excludeDefaultHttpClients() {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }

    api(libs.aws.cloudWatch) { excludeDefaultHttpClients() }
    // Sync path uses the JDK-based url-connection-client (no transitive deps); async path uses
    // netty-nio-client (Netty is already present in typical deployments via the server engine).
    api(libs.aws.urlConnectionClient)
    api(libs.aws.nettyNioClient)
    testImplementation(libs.coroutines.testing)
    testImplementation(project(":cache-test"))
}

kotlin {
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
    description.set("A tool for establishing AWS connections.")
}
