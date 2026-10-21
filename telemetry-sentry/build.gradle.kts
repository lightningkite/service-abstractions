import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":basis"))
    api(libs.sentry)
    implementation(libs.openTelemetry.api)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
    testImplementation(libs.openTelemetry.sdk)
    testRuntimeOnly(libs.logBackClassic)
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
    description.set("Sends reported errors to Sentry.")
}
