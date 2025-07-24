
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
import com.lightningkite.deployhelpers.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    // alias(libs.plugins.dokka)
    alias(libs.plugins.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(":database"))
    api(project(":database-processor"))
    api(project(":database-test"))
    api(project(":database-mongodb"))
    api(project(":database-jsonfile"))
    api(project(":database-postgres"))
    api(project(":email"))
    api(project(":email-test"))
    api(project(":email-mailgun"))
    api(project(":sms"))
    api(project(":sms-test"))
    api(project(":sms-twilio"))
    api(project(":metrics"))
    api(project(":metrics-test"))
    api(project(":metrics-cloudwatch"))
    api(project(":notifications"))
    api(project(":notifications-test"))
    api(project(":notifications-fcm"))
    api(project(":cache"))
    api(project(":cache-test"))
    api(project(":cache-dynamodb"))
    api(project(":cache-memcached"))
    api(project(":cache-redis"))
    api(project(":pubsub"))
    api(project(":pubsub-test"))
    api(project(":pubsub-redis"))
    api(project(":files"))
    api(project(":files-test"))
    api(project(":files-clamav"))
    api(project(":files-azbs"))
    api(project(":files-s3"))
    api(project(":exceptions"))
    api(project(":exceptions-test"))
    api(project(":exceptions-sentry"))

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
