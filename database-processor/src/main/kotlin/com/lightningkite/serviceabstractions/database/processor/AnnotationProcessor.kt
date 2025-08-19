package com.lightningkite.services.database.processor

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.lightningkite.services.database.processor.imports
import kotlinx.coroutines.flow.merge
import org.jetbrains.kotlin.util.capitalizeDecapitalize.decapitalizeAsciiOnly
import java.io.BufferedWriter
import java.io.File
import java.util.*
import java.util.Locale
import java.util.Locale.getDefault
import kotlin.collections.HashSet
import kotlin.collections.distinct
import kotlin.collections.plus
import kotlin.text.appendLine
import kotlin.text.substringAfterLast


class TableGenerator(
    val codeGenerator: CodeGenerator,
    val logger: KSPLogger,
) : CommonSymbolProcessor2(codeGenerator, "lightningdb", 9) {
    fun KSClassDeclaration.needsDcp(): Boolean =
        annotation("DatabaseModel") != null || annotation("GenerateDataClassPaths") != null

    override fun interestedIn(resolver: Resolver): Set<KSFile> {
        return resolver.getAllFiles()
            .filter {
                it.declarations
                    .filterIsInstance<KSClassDeclaration>()
                    .flatMap { sequenceOf(it) + it.declarations.filterIsInstance<KSClassDeclaration>() }
                    .any { it.needsDcp() }
            }
            .toSet()
    }

    @OptIn(KspExperimental::class)
    override fun process2(resolver: Resolver, files: Set<KSFile>) {
        if (files.isEmpty()) return
        files
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { sequenceOf(it) + it.declarations.filterIsInstance<KSClassDeclaration>() }
            .filter { it.needsDcp() }
            .groupBy { it.packageName.asString() }
            .forEach { (packageName, classes) ->
                val files = classes.asSequence().mapNotNull { it.containingFile }.distinct().sortedBy { it.fileName }
                createNewFile(
                    dependencies = Dependencies(false, *files.toList().toTypedArray()),
                    packageName = packageName,
                    fileName = "ModelFields${classes.mapTo(HashSet()) { it.simpleName.asString() }.hashCode()}"
                ).use { out ->
                    with(TabAppendable(out)) {
                        appendLine("""// Automatically generated from classes ${classes.joinToString { it.simpleName.asString() }}. Do not modify!""")
                        appendLine("""@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)""")
                        appendLine("""@file:Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER", "UnusedImport")""")
                        appendLine()
                        if (packageName.isNotEmpty()) appendLine("package ${packageName}")
                        appendLine()
                        try {
                            files.flatMap { it.imports }
                                .plus(
                                    listOf(
                                        "com.lightningkite.services.database.*",
                                        "com.lightningkite.services.data.*",
                                        "kotlin.reflect.*",
                                        "kotlinx.serialization.*",
                                        "kotlinx.serialization.builtins.*",
                                        "kotlinx.serialization.internal.GeneratedSerializer",
                                        "kotlinx.datetime.*",
                                        "com.lightningkite.*",
                                        "kotlin.jvm.JvmName",
                                    )
                                )
                                .distinct()
                                .filter {
                                    it.substringAfterLast('.').let {
                                        !(it.startsWith("prepare") && it.endsWith("Fields")
                                                || it.startsWith("prepareModels"))
                                    }
                                }
                                .forEach { appendLine("import $it") }
                        } catch (e: Exception) {
                            appendLine("/*" + e.stackTraceToString() + "*/")
                        }
                        appendLine()
                        val contextualTypes = files.flatMap {
                            it.annotation(
                                "UseContextualSerialization",
                                "kotlinx.serialization"
                            )?.arguments?.firstOrNull()
                                ?.value
                                ?.let {
                                    @Suppress("UNCHECKED_CAST")
                                    it as? List<KSType>
                                }
                                ?.map { it.declaration }
                                ?: listOf()
                        }
                        appendLine("// Contextual types: ${contextualTypes.joinToString { it.qualifiedName?.asString() ?: "-" }}")

                        for(declaration in classes) {
                            val classReference: String = declaration.safeLocalReference()
                            val fields = declaration.fields()
                            val typeReference: String = declaration.safeLocalReference() + (declaration.typeParameters.takeUnless { it.isEmpty() }
                                ?.joinToString(", ", "<", ">") {  it.name.asString() } ?: "")
                            val simpleName: String = declaration.simpleName.getShortName()

                            if(declaration.typeParameters.isNotEmpty()) {
                                appendLine("inline fun <${declaration.typeParameters.joinToString(", ") {
                                    "reified " + it.name.asString() + ": " + (it.bounds.firstOrNull()?.toKotlin() ?: "Any?")
                                }}> $classReference.Companion.path(): DataClassPath<$typeReference, $typeReference> = com.lightningkite.services.database.path<$typeReference>()")
                                for ((index, field) in fields.withIndex()) {
                                    val serPropName = "field${
                                        field.name.replaceFirstChar {
                                            if (it.isLowerCase()) it.titlecase(
                                                getDefault()
                                            ) else it.toString()
                                        }
                                    }"
                                    appendLine("val <${declaration.typeParameters.joinToString(", ") {
                                        it.name.asString() + ": " + (it.bounds.firstOrNull()?.toKotlin() ?: "Any?")
                                    }}> KSerializer<${typeReference}>.$serPropName: SerializableProperty<$typeReference, ${field.kotlinType.toKotlin()}> get() = SerializableProperty.Generated(this as GeneratedSerializer<$typeReference>, $index)")
                                    appendLine(
                                        "@get:JvmName(\"path$serPropName\") val <ROOT, ${
                                            declaration.typeParameters.joinToString(", ") {
                                                it.name.asString() + ": " + (it.bounds.firstOrNull()?.toKotlin() ?: "Any?")
                                            }
                                        }> DataClassPath<ROOT, $typeReference>.${field.name}: DataClassPath<ROOT, ${field.kotlinType.toKotlin()}> get() = this[this.serializer.$serPropName]"
                                    )
                                }
                            } else {
                                appendLine("inline val $typeReference.Companion.path: DataClassPath<$typeReference, $typeReference> get() = com.lightningkite.services.database.path<$typeReference>()")
                                appendLine("private val ${simpleName}__properties = $classReference.serializer().serializableProperties!!")
                                for ((index, field) in fields.withIndex()) {
                                    val serPropName = "${simpleName}_${field.name}"
                                    appendLine("val $serPropName: SerializableProperty<$typeReference, ${field.kotlinType.toKotlin()}> = ${simpleName}__properties[$index] as SerializableProperty<$typeReference, ${field.kotlinType.toKotlin()}>")
                                    appendLine("@get:JvmName(\"path$serPropName\") val <ROOT> DataClassPath<ROOT, $typeReference>.${field.name}: DataClassPath<ROOT, ${field.kotlinType.toKotlin()}> get() = this[$serPropName]")
                                }
                            }
                        }
                    }
                }
            }

        logger.info("Complete.")
    }
}

class MyProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return TableGenerator(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
        )
    }
}