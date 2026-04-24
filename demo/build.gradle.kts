plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
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

    testImplementation(libs.coroutines.testing)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.set(listOf("-Xcontext-parameters"))
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
