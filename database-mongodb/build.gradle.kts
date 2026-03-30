
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
    api(project(path = ":basis"))
    api(project(path = ":database"))
    testImplementation(project(path = ":database-test"))
    implementation(libs.kotlin.reflect)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
    testImplementation(libs.testContainers)
    testImplementation(libs.testContainers.mongodb)
    testImplementation(libs.testContainers.junit)
    implementation(libs.embedded.mongo)
    implementation(libs.mongo.driver)
    implementation(libs.mongo.driver.otel)
//    implementation(libs.ktMongo)
//    implementation(libs.ktMongoMultiplatform)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi"); freeCompilerArgs.set(listOf("-Xcontext-parameters"))
    }
    explicitApi()
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


lkLibrary("lightningkite", "service-abstractions") {}
