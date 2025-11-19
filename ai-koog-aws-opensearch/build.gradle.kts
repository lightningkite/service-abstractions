import com.lightningkite.deployhelpers.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.dokka)
    alias(libs.plugins.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":basis"))
    api(project(path = ":ai-koog"))
    testImplementation(libs.kotlinTest)
    testImplementation(libs.coroutinesTesting)

    // OpenSearch Java client for vector storage
    implementation(libs.openSearchJava)

    // AWS SDK for OpenSearch (optional, for AWS-specific features)
    implementation(libs.openSearchAws)

    // Apache HTTP Client required by OpenSearch Java client
    implementation("org.apache.httpcomponents.client5:httpclient5:5.4.1")
    implementation("org.apache.httpcomponents.core5:httpcore5:5.3.1")
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.set(listOf("-Xcontext-parameters"))
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
