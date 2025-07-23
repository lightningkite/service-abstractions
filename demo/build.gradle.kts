
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
import com.lightningkite.deployhelpers.*
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    // alias(libs.plugins.dokka)
    alias(libs.plugins.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":database"))
    api(project(path = ":database-mongodb"))
    api(project(path = ":database-jsonfile"))
    api(project(path = ":database-postgres"))
    api(project(path = ":email"))
    api(project(path = ":email-mailgun"))
    api(project(path = ":sms"))
    api(project(path = ":sms-twilio"))
    api(project(path = ":metrics"))
    api(project(path = ":metrics-cloudwatch"))
    api(project(path = ":notifications"))
    api(project(path = ":notifications-fcm"))
    api(project(path = ":cache"))
    api(project(path = ":cache-dynamodb"))
    api(project(path = ":cache-memcached"))
    api(project(path = ":cache-redis"))
    api(project(path = ":pubsub"))
    api(project(path = ":pubsub-redis"))
    api(project(path = ":files"))
    api(project(path = ":files-clamav"))
    api(project(path = ":files-azbs"))
    api(project(path = ":files-s3"))
    api(project(path = ":exceptions"))
    api(project(path = ":exceptions-sentry"))

    testImplementation(libs.coroutinesTesting)
}

kotlin {
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}

tasks.withType<JavaCompile>().configureEach {
    this.targetCompatibility = "17"
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
    kotlinOptions.jvmTarget = JavaVersion.VERSION_17.toString()
}

mavenPublishing {
    // publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(group.toString(), name, version.toString())
    pom {
        name.set("Service Abstractions - $name")
        description.set(description)
        github("lightningkite", "service-abstractions")
        licenses {
            mit()
        }
        developers {
            joseph()
            brady()
        }
    }
}
