import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    // The pure-Kotlin Bedrock client whose signing/event-stream code we reuse; we only swap in
    // SDK-backed credential resolution.
    api(project(path = ":ai-bedrock"))

    // Just the AWS SDK credential providers, not a full service client. signin is what resolves
    // `aws login` profiles; SSO profiles would also need sso + ssooidc, and role_arn profiles sts.
    api(libs.aws.auth)
    api(libs.aws.signin)
    implementation(libs.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
    explicitApi()
}

tasks.withType<JavaCompile>().configureEach {
    this.targetCompatibility = "17"
}

lkLibrary(
    "lightningkite",
    "service-abstractions",
    mavenAutomaticRelease = project.findProperty("mavenAutomaticRelease") as? Boolean ?: false
) {
    description.set("JVM-only AWS SDK credential resolution (profiles including aws login, container and instance roles) for the Bedrock LlmAccess.")
}
