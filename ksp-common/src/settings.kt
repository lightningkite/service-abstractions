package kspcommon

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.Configurable

/** Per-module configuration for the `ksp-common` plugin. */
@Configurable
interface KspCommonSettings {
    /**
     * The local modules whose types the processor has to resolve, as a classpath.
     *
     * `${module.compileClasspath}` resolves a module's external Maven artifacts but not the
     * output of the modules it depends on, and without those the processor cannot resolve
     * `@GenerateDataClassPaths` and silently generates nothing. Each consumer therefore lists
     * its own local dependencies here; a shared list would create a task dependency loop.
     */
    val localDependencies: Classpath

    /** The same, for the module's test sources. Usually adds the test-only module dependencies. */
    val testLocalDependencies: Classpath
}
