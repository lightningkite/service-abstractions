import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":files"))
    implementation(libs.tikaCore) {
        // Only Tika's Markdown output (ToMarkdownContentHandler) uses commonmark; type detection never loads it.
        exclude(group = "org.commonmark")
    }
    testImplementation(project(path = ":test"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
}

kotlin {
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
    description.set("A FileScanner implementation that detects file types from their content using Apache Tika")
}
