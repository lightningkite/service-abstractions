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
        id("com.google.devtools.ksp") version "2.2.20-2.0.4"
        id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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

        versionCatalogs {
            create("awssdk") {
                from("aws.sdk.kotlin:version-catalog:1.5.39")
            }
        }
    }
}

include(":aws-client")
include(":basis")
include(":cache")
include(":cache-dynamodb")
//include(":cache-dynamodb-kmp")
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
include(":database-cassandra")
//include(":database-migration")   // EXPERIMENTAL
include(":demo")
include(":email")
include(":email-javasmtp")
include(":email-test")
include(":exceptions-sentry")
include(":files")
include(":files-clamav")
include(":files-client")
include(":files-s3")
include(":files-s3-kmp")
include(":files-test")
include(":http-client")
include(":metrics-cloudwatch")
include(":notifications")
include(":notifications-fcm")
include(":notifications-test")
include(":pubsub")
include(":pubsub-aws")
//include(":pubsub-mqtt")  // EXPERIMENTAL
//include(":pubsub-mqtt-paho")  // EXPERIMENTAL
//include(":pubsub-mqtt-aws")  // EXPERIMENTAL
include(":pubsub-redis")
include(":pubsub-test")
include(":should-be-standard-library")
include(":sms")
include(":sms-test")
include(":sms-twilio")
include(":test")
include(":otel-jvm")
include(":ai-koog")
include(":email-inbound")
include(":email-inbound-sendgrid")
include(":email-inbound-mailgun")
include(":email-inbound-ses")
include(":email-inbound-imap")
include(":sms-inbound")
include(":sms-inbound-twilio")
include(":phonecall")
include(":phonecall-twilio")
include(":phonecall-test")
include(":speech")
include(":speech-elevenlabs")
include(":speech-local")
include(":speech-openai")
include(":speech-test")
include(":subscription-payments")
include(":subscription-payments-stripe")
include(":subscription-payments-test")
include(":voiceagent")
include(":voiceagent-openai")
include(":voiceagent-phonecall")
include(":voiceagent-test")
