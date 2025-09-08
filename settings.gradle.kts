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

//include(":email-mailgun")
//include(":files-azbs")
//include(":files-javalocal")
include(":aws-client")
include(":basis")
include(":cache")
include(":cache-dynamodb")
include(":cache-memcached")
include(":cache-redis")
include(":cache-test")
include(":data")
include(":database")
include(":database-shared")
include(":database-jsonfile")
include(":database-mongodb")
include(":database-postgres")
include(":database-processor")
include(":database-test")
include(":demo")
include(":email")
include(":email-javasmtp")
include(":email-test")
include(":exceptions-sentry")
include(":files")
include(":files-clamav")
include(":files-client")
include(":files-s3")
include(":files-test")
include(":http-client")
include(":metrics-cloudwatch")
include(":notifications")
include(":notifications-fcm")
include(":notifications-test")
include(":pubsub")
include(":pubsub-redis")
include(":pubsub-test")
include(":should-be-standard-library")
include(":sms")
include(":sms-test")
include(":sms-twilio")
include(":test")