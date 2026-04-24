import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":basis"))
    fun ModuleDependency.excludeNetty() {
        exclude("software.amazon.awssdk:netty-nio-client")
        exclude("software.amazon.awssdk:apache-client")
    }

    api(libs.aws.cloudWatch) { excludeNetty() }
    api(libs.aws.crt.client) { excludeNetty() }
    api(libs.aws.otel) { excludeNetty() }
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
