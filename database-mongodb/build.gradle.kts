import com.lightningkite.deployhelpers.lkLibrary
import java.io.File


plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":database"))
    testImplementation(project(path = ":database-test"))
    implementation(libs.kotlin.reflect)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
    testImplementation(libs.testContainers)
    testImplementation(libs.testContainers.mongodb)
    testImplementation(libs.testContainers.junit)
    implementation(libs.embedded.mongo)
    implementation(libs.mongo.driver)
    // Telemetry/span-parenting tests construct OtelMetricsBackend; main code uses only the vendor-neutral MetricsBackend API.
    testImplementation(project(":otel-jvm"))
    testImplementation(libs.openTelemetry.sdk)
    testImplementation(libs.openTelemetry.sdk.testing)
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

tasks.withType<Test>().configureEach {
    // On macOS with Docker Desktop, /var/run/docker.sock is a broken symlink from the JVM's
    // perspective. Use the raw socket for API calls and tell Testcontainers to use the standard
    // symlink for Ryuk's socket mount (Docker Desktop handles that mount correctly inside the VM).
    if (System.getenv("DOCKER_HOST") == null) {
        val home = System.getProperty("user.home")
        val rawSocket = "$home/Library/Containers/com.docker.docker/Data/docker.raw.sock"
        if (File(rawSocket).exists()) {
            environment("DOCKER_HOST", "unix://$rawSocket")
            environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
        } else {
            listOf(
                "$home/.docker/run/docker.sock",
                "/var/run/docker.sock",
                "$home/.colima/default/docker.sock",
            ).firstOrNull { File(it).exists() }
                ?.let { environment("DOCKER_HOST", "unix://$it") }
        }
    }
}


lkLibrary(
    "lightningkite",
    "service-abstractions",
    mavenAutomaticRelease = project.findProperty("mavenAutomaticRelease") as? Boolean ?: false
) {
    description.set("A database implementation using MongoDB.")
}
