plugins {
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.androidApp) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.graalVmNative) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.versionCatalogUpdate)
    id("org.jetbrains.kotlinx.atomicfu") version "0.32.1"
}

buildscript {
    repositories {
        mavenLocal()
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
    }
    dependencies {
        classpath(libs.lkGradleHelpers)
        classpath(libs.proguard)
    }
}

allprojects {
    group = "com.lightningkite.services"

    repositories {
        mavenLocal()
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
        google()
        mavenCentral()
    }
}

// All telemetry must flow through the vendor-neutral MetricsBackend API (com.lightningkite.services.metricsTrace,
// metricsHistogram, etc.). The OpenTelemetry SDK is an implementation detail confined to :otel-jvm. This task
// fails the build if any non-test production source outside otel-jvm reaches for OpenTelemetry directly.
val verifyNoDirectOtel by tasks.registering {
    group = "verification"
    description = "Fails if production code outside otel-jvm uses OpenTelemetry directly instead of the MetricsBackend API."
    val root = rootDir
    doLast {
        // Forbidden: the OTel SDK itself, and the otel-jvm OpenTelemetrySub wrapper. The pure-string
        // TelemetrySanitization helper is intentionally allowed (it exposes no OpenTelemetry types).
        val forbidden = Regex("""import\s+io\.opentelemetry|OpenTelemetrySub""")
        val testSourceSet = Regex("""/src/(test|[^/]*Test)/""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val path = file.invariantSeparatorsPath
                "/src/" in path && "/build/" !in path && "/otel-jvm/" !in path &&
                    !testSourceSet.containsMatchIn(path)
            }
            .filter { forbidden.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }
            .toList()
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Direct OpenTelemetry usage found outside :otel-jvm — route telemetry through the " +
                    "MetricsBackend API (metricsTrace/metricsHistogram/...):\n" +
                    offenders.joinToString("\n") { "  - $it" },
            )
        }
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyNoDirectOtel) }

    // Expensive tests — live-service suites that need a running local server (Ollama, LM Studio)
    // or a keyed cloud provider (Anthropic, OpenAI, Bedrock) — live in an `integration` package.
    // They are slow, machine-dependent, and sometimes billed, so the normal test run excludes them.
    // Run them deliberately with `-Pexpensive`:
    //     ./gradlew :ai-ollama:test -Pexpensive --tests '*.integration.*'
    // Running a single suite from the IDE is unaffected: the IDE invokes JUnit directly and never
    // applies this filter. The suites' own `servicePresent` probes still skip when a server is down.
    if (!project.hasProperty("expensive")) {
        tasks.withType<Test>().configureEach {
            filter {
                excludeTestsMatching("*.integration.*")
                isFailOnNoMatchingTests = false
            }
        }
    }
}
