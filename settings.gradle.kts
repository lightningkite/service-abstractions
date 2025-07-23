rootProject.name = "lightning-server"

pluginManagement {
    repositories {
        mavenLocal()
        google()
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") }
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap") }
        maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
    }

    plugins {
        kotlin("plugin.serialization") version "2.0.0"
        id("com.google.devtools.ksp") version "2.0.21-1.0.25"
    }

    dependencyResolutionManagement {
        repositories {
            mavenLocal()
            maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
            google()
            gradlePluginPortal()
            mavenCentral()
            maven("https://jitpack.io")
        }

    }
}

include(":demo")
include(":basis")
include(":database")
include(":database-mongodb")
include(":database-jsonfile")
include(":database-postgres")
include(":email")
include(":email-mailgun")
include(":sms")
include(":sms-twilio")
include(":metrics")
include(":metrics-cloudwatch")
include(":notifications")
include(":notifications-fcm")
include(":cache")
include(":cache-dynamodb")
include(":cache-memcached")
include(":cache-redis")
include(":pubsub")
include(":pubsub-redis")
include(":files")
include(":files-clamav")
include(":files-azbs")
include(":files-s3")
include(":exceptions")
include(":exceptions-sentry")
