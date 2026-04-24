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

//include(":cache-dynamodb-kmp")
//include(":database-cassandra")   // EXPERIMENTAL
//include(":database-migration")   // EXPERIMENTAL
//include(":files-s3-kmp") // EXPERIMENTAL
//include(":pubsub-mqtt")  // EXPERIMENTAL
//include(":pubsub-mqtt-aws")  // EXPERIMENTAL
//include(":pubsub-mqtt-paho")  // EXPERIMENTAL

include(":ai-koog")
include(":aws-client")
include(":basis")
include(":cache")
include(":cache-dynamodb")
include(":cache-memcached")
include(":cache-redis")
include(":cache-test")
include(":data")
include(":data-shared")
include(":database")
include(":database-jsonfile")
include(":database-mongodb")
include(":database-postgres")
include(":database-processor")
include(":database-shared")
include(":database-test")
include(":demo")
include(":email")
include(":email-inbound")
include(":email-inbound-imap")
include(":email-inbound-mailgun")
include(":email-inbound-sendgrid")
include(":email-inbound-ses")
include(":email-javasmtp")
include(":email-mailgun")
include(":email-test")
include(":files")
include(":files-clamav")
include(":files-client")
include(":files-s3")
include(":files-test")
include(":http-client")
include(":kfile")
include(":notifications")
include(":notifications-fcm")
include(":notifications-test")
include(":otel-jvm")
include(":phonecall")
include(":phonecall-test")
include(":phonecall-twilio")
include(":pubsub")
include(":pubsub-aws")
include(":pubsub-redis")
include(":pubsub-test")
include(":kotlin-bytes-format")
include(":sms")
include(":sms-inbound")
include(":sms-inbound-twilio")
include(":sms-test")
include(":sms-twilio")
include(":speech")
include(":speech-elevenlabs")
include(":speech-local")
include(":speech-openai")
include(":speech-test")
include(":subscription-payments")
include(":subscription-payments-stripe")
include(":subscription-payments-test")
include(":test")
include(":voiceagent")
include(":voiceagent-openai")
include(":voiceagent-phonecall")
include(":voiceagent-test")
include(":webhook-subservice")
