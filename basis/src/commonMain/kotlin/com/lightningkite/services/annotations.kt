package com.lightningkite.services

/**
 * Marks experimental or untested APIs that may have issues or change in the future.
 *
 * Features marked with [Untested] have been implemented but not thoroughly validated.
 * Use them with caution and at your own risk. They may:
 * - Have undiscovered bugs
 * - Perform poorly in production scenarios
 * - Change behavior in future versions without warning
 * - Lack proper error handling
 *
 * ## Usage
 *
 * To use an [Untested] API, you must either:
 *
 * 1. Opt-in at the call site:
 * ```kotlin
 * @OptIn(Untested::class)
 * fun myFunction() {
 *     unstedFeature()
 * }
 * ```
 *
 * 2. Opt-in for the entire file:
 * ```kotlin
 * @file:OptIn(Untested::class)
 * package com.example
 * ```
 *
 * 3. Opt-in at the module level (in build.gradle.kts):
 * ```kotlin
 * kotlin {
 *     sourceSets.all {
 *         languageSettings.optIn("com.lightningkite.services.Untested")
 *     }
 * }
 * ```
 *
 * ## When This is Used
 *
 * Library developers mark features as [Untested] when:
 * - New service implementations have been added but not battle-tested
 * - Experimental features need real-world feedback
 * - Platform-specific code hasn't been validated on all targets
 * - Integration with new third-party services is preliminary
 *
 * Report any issues you encounter to help stabilize these features!
 *
 * @see RequiresOptIn for the underlying mechanism
 */
@RequiresOptIn("We haven't tested this yet. Use at your own risk.", RequiresOptIn.Level.WARNING)
public annotation class Untested