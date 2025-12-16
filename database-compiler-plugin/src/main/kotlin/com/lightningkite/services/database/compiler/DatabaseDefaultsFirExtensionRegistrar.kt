package com.lightningkite.services.database.compiler

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

public class DatabaseDefaultsFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        // TODO: Add FIR extensions here if needed for analyzing/transforming declarations
        // For now, we'll primarily use IR generation
    }
}
