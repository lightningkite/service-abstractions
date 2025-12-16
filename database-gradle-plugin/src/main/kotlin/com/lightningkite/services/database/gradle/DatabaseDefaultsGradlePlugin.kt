package com.lightningkite.services.database.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Gradle plugin that sets up the Database Defaults system.
 *
 * This plugin automatically:
 * 1. Adds the database-compiler-plugin to inject SerializablePropertiesProvider interface
 * 2. Applies KSP plugin (if not already applied)
 * 3. Adds database-processor as a KSP dependency to generate __serializableProperties arrays
 *
 * Usage in build.gradle.kts:
 * ```kotlin
 * plugins {
 *     id("com.lightningkite.serviceabstractions.database-defaults") version "x.x.x"
 * }
 * ```
 */
public class DatabaseDefaultsGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        // Apply KSP plugin if not already applied
        target.pluginManager.apply("com.google.devtools.ksp")

        // Add database-processor to all KSP configurations
        target.afterEvaluate {
            target.configurations
                .filter { it.name.startsWith("ksp") }
                .forEach { config ->
                    target.dependencies.add(
                        config.name,
                        "com.lightningkite.services:database-processor:${BuildConfig.VERSION}"
                    )
                }
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String =
        "com.lightningkite.serviceabstractions.database-defaults"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "com.lightningkite.services",
        artifactId = "database-compiler-plugin",
        version = BuildConfig.VERSION
    )

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> =
        kotlinCompilation.target.project.provider { emptyList() }
}
