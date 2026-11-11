import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.androidApp) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.graalVmNative) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.versionCatalogUpdate)
    id("org.jetbrains.kotlinx.atomicfu") version "0.33.0"
    `version-catalog`
    `maven-publish`
}

buildscript {
    repositories {
        mavenLocal()
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
    }
    dependencies {
        classpath(libs.proguard)
    }
}

allprojects {
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

// Every published module: group and version come from its gradle.properties.  `lk` (in metawork) is the only thing
// that edits the version.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        repositories {
            maven("https://jitpack.io")
        }

        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = false)
            signAllPublications()
            // Dokka is slow, so the javadoc jar is built only on request.
            configureBasedOnAppliedPlugins(sourcesJar = true, javadocJar = providers.gradleProperty("withDocs").isPresent)
            pom {
                name.set(project.name)
                url.set("https://lightningkite-maven.s3.amazonaws.com/${group.toString().replace('.', '/')}/${project.name}/$version/docs/index.html")
                scm {
                    connection.set("scm:git:https://github.com/lightningkite/service-abstractions.git")
                    developerConnection.set("scm:git:https://github.com/lightningkite/service-abstractions.git")
                    url.set("https://github.com/lightningkite/service-abstractions")
                }
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer { id.set("LightningKiteJoseph"); name.set("Joseph Ivie"); email.set("joseph@lightningkite.com") }
                    developer { id.set("bjsvedin"); name.set("Brady Svedin"); email.set("brady@lightningkite.com") }
                    developer { id.set("iHoonter"); name.set("Hunter"); email.set("Hunter@lightningkite.com") }
                }
            }
        }

        extensions.configure<SigningExtension> {
            val signingKey = providers.gradleProperty("signingKey").orNull
            if (signingKey != null) useInMemoryPgpKeys(signingKey, providers.gradleProperty("signingPassword").orNull)
        }
        tasks.withType<Sign>().configureEach {
            val hasKey = providers.gradleProperty("signingKey").isPresent
            onlyIf("a signing key is configured") { hasKey }
        }

        extensions.configure<PublishingExtension> {
            repositories {
                maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("staging-repo")) }
            }
        }

        plugins.withId("org.jetbrains.dokka") {
            dependencies.add("dokkaPlugin", "com.lightningkite:dokka-plugin-hide-optin:0.0.4")
            extensions.configure<DokkaExtension> {
                dokkaSourceSets.configureEach {
                    sourceRoots.map { it.resolve("module.md") }.firstOrNull { it.exists() }?.let { includes.from(it) }
                    sourceLink {
                        localDirectory.set(file("src/$name/kotlin"))
                        remoteUrl.set(uri("https://github.com/lightningkite/service-abstractions/tree/master/"))
                        remoteLineSuffix.set("#L")
                    }
                }
            }
        }

        // `lk` compares a fresh dump with the last release's to choose the next version, so no dumps are committed or checked.
        plugins.withId("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<KotlinMultiplatformExtension> {
                @OptIn(ExperimentalAbiValidation::class)
                abiValidation()
            }
        }
        plugins.withId("org.jetbrains.kotlin.jvm") {
            extensions.configure<KotlinJvmProjectExtension> {
                @OptIn(ExperimentalAbiValidation::class)
                abiValidation()
            }
        }
        tasks.matching { it.name == "checkKotlinAbi" }.configureEach { enabled = false }
    }
}

// The catalog downstream repos pin: this repo's modules at the versions being published.
val published = subprojects.filter { it.file("gradle.properties").let { f -> f.isFile && f.readText().contains(Regex("(?m)^version=")) } }
catalog { versionCatalog { published.forEach { library(it.name, "${it.group}:${it.name}:${it.version}") } } }
publishing {
    publications.create<MavenPublication>("catalog") {
        from(components["versionCatalog"])
        groupId = "com.lightningkite.stack"
        artifactId = "service-abstractions-catalog"
        version = providers.gradleProperty("catalogVersion").getOrElse("unspecified")
    }
    repositories { maven { name = "staging"; url = uri(layout.buildDirectory.dir("staging-repo")) } }
}

tasks.register("test") {
    group = "custom"

    // Run for root multi-project subprojects
    dependsOn(provider {
        subprojects.mapNotNull { it.tasks.findByName("test") }
    })

    // Safely trigger for composite builds
    gradle.includedBuilds.forEach { included ->
        dependsOn(included.task(":test"))
    }
}