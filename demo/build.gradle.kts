import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
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
    api(project(":sms"))
    api(project(":sms-twilio"))
    api(project(":notifications"))
    api(project(":notifications-fcm"))
    api(project(":cache"))
    api(project(":cache-dynamodb"))
    api(project(":cache-memcached"))
    api(project(":cache-redis"))
    api(project(":pubsub"))
    api(project(":pubsub-redis"))
    api(project(":files"))
    api(project(":files-clamav"))
    api(project(":files-s3"))
    api(project(":human-services"))

    testImplementation(libs.coroutines.testing)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi"); freeCompilerArgs.set(listOf("-Xcontext-parameters"))
    }
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


tasks.register<JavaExec>("runHumanDemo") {
    description = "Runs the Human Services Dashboard demo on http://localhost:8800"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.lightningkite.services.demo.HumanServicesDemoKt")
}

lkLibrary("lightningkite", "service-abstractions") {}
