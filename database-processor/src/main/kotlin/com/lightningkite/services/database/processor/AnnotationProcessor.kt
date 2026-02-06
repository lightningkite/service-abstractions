package com.lightningkite.services.database.processor

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import java.util.Locale.getDefault
import kotlin.collections.HashSet
import kotlin.text.substringAfterLast

/**
 * KSP (Kotlin Symbol Processor) that generates type-safe DataClassPath field accessors.
 *
 * This processor scans for classes annotated with @GenerateDataClassPaths or @DatabaseModel
 * and generates extension properties that enable type-safe field references in database queries.
 *
 * ## What It Generates
 *
 * For a model like:
 * ```kotlin
 * @GenerateDataClassPaths
 * @Serializable
 * data class User(val name: String, val age: Int)
 * ```
 *
 * It generates:
 * ```kotlin
 * val User.Companion.path: DataClassPath<User, User>
 * val User_name: SerializableProperty<User, String>
 * val User_age: SerializableProperty<User, Int>
 * val <ROOT> DataClassPath<ROOT, User>.name: DataClassPath<ROOT, String>
 * val <ROOT> DataClassPath<ROOT, User>.age: DataClassPath<ROOT, Int>
 * ```
 *
 * This enables queries like: `User.path.age gte 18`
 *
 * ## Processing Flow
 *
 * 1. Scans all Kotlin source files for annotated classes
 * 2. Groups classes by package
 * 3. Generates ModelFields{hash}.kt files with path accessors
 * 4. Caches results to avoid regeneration when sources unchanged
 *
 * ## Important Implementation Details
 *
 * - **Hash-based caching**: Uses file content checksums to skip regeneration
 * - **Generic type support**: Handles type parameters correctly
 * - **Lock files**: Prevents concurrent generation in multi-module builds
 * - **Common/platform**: Generates code in commonMain when applicable
 *
 * @see com.lightningkite.services.data.GenerateDataClassPaths
 */
class TableGenerator(
    val codeGenerator: CodeGenerator,
    val logger: KSPLogger,
) : CommonSymbolProcessor2(codeGenerator, "lightningdb", 10) {
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

                        for (declaration in classes) {
                            try {
                                val classReference: String = declaration.safeLocalReference()
                                val fields = declaration.fields()
                                val typeReference: String =
                                    declaration.safeLocalReference() + (declaration.typeParameters.takeUnless { it.isEmpty() }
                                        ?.joinToString(", ", "<", ">") { it.name.asString() } ?: "")
                                val simpleName: String = declaration.simpleName.getShortName()

                                if (declaration.typeParameters.isNotEmpty()) {
                                    appendLine(
                                        "public inline fun <${
                                            declaration.typeParameters.joinToString(", ") {
                                                "reified " + it.name.asString() + ": " + (it.bounds.firstOrNull()
                                                    ?.toKotlin() ?: "Any?")
                                            }
                                        }> $classReference.Companion.path(): DataClassPath<$typeReference, $typeReference> = com.lightningkite.services.database.path<$typeReference>()"
                                    )

                                    listOf(
                                        "public fun",
                                        declaration.typeParameters.joinToString(
                                            ", ",
                                            prefix = " <",
                                            postfix = "> "
                                        ) { param ->
                                            param.name.asString() + (param.bounds.firstOrNull()?.toKotlin()
                                                ?.let { ": $it" } ?: "")
                                        },
                                        "$classReference.Companion.path(",
                                        declaration.typeParameters.joinToString(", ") { param ->
                                            "${
                                                param.name.asString().lowercase()
                                            }: KSerializer<${param.name.asString()}>"
                                        },
                                        "): DataClassPath<$typeReference, $typeReference>",
                                        " = ",
                                        "com.lightningkite.services.database.path",
                                        declaration.typeParameters.joinToString(
                                            ", ",
                                            prefix = "($classReference.Companion.serializer(",
                                            postfix = "))"
                                        ) {
                                            it.name.asString().lowercase()
                                        },
                                        "\n"
                                    ).forEach(::append)

                                    for ((index, field) in fields.withIndex()) {
                                        val propName = field.name.replaceFirstChar {
                                            if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString()
                                        }
                                        val serPropName = "field$propName"

                                        val prefix = declaration.safeLocalReference().camelCase()
                                        appendLine(
                                            "@get:JvmName(\"${prefix}_field_$propName\") public val <${
                                                declaration.typeParameters.joinToString(", ") {
                                                    it.name.asString() + ": " + (it.bounds.firstOrNull()
                                                        ?.toKotlin() ?: "Any?")
                                                }
                                            }> KSerializer<${typeReference}>.$serPropName: SerializableProperty<$typeReference, ${field.kotlinType.toKotlin()}> get() = SerializableProperty.Generated(this as GeneratedSerializer<$typeReference>, $index)"
                                        )
                                        appendLine(
                                            "@get:JvmName(\"${prefix}_path_$propName\") public val <ROOT, ${
                                                declaration.typeParameters.joinToString(", ") {
                                                    it.name.asString() + ": " + (it.bounds.firstOrNull()
                                                        ?.toKotlin() ?: "Any?")
                                                }
                                            }> DataClassPath<ROOT, $typeReference>.${field.name}: DataClassPath<ROOT, ${field.kotlinType.toKotlin()}> get() = this[this.serializer.$serPropName]"
                                        )
                                    }
                                } else {
                                    appendLine("public inline val $typeReference.Companion.path: DataClassPath<$typeReference, $typeReference> get() = com.lightningkite.services.database.path<$typeReference>()")
                                    appendLine("private val ${simpleName}__properties = $classReference.serializer().serializableProperties!!")
                                    for ((index, field) in fields.withIndex()) {
                                        val serPropName = "${simpleName}_${field.name}"
                                        appendLine("public val $serPropName: SerializableProperty<$typeReference, ${field.kotlinType.toKotlin()}> = ${simpleName}__properties[$index] as SerializableProperty<$typeReference, ${field.kotlinType.toKotlin()}>")
                                        appendLine("@get:JvmName(\"path$serPropName\") public val <ROOT> DataClassPath<ROOT, $typeReference>.${field.name}: DataClassPath<ROOT, ${field.kotlinType.toKotlin()}> get() = this[$serPropName]")
                                    }
                                }
                            } catch (e: Exception) {
                                appendLine("/*" + e.stackTraceToString() + "*/")
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

// TODO: API Recommendation - Add incremental processing support
//  KSP supports incremental processing to speed up builds, but this processor uses ALL_FILES dependencies.
//  Consider tracking per-file dependencies to enable incremental builds in large projects.
//  This would significantly improve developer experience during iterative development.
//
// TODO: API Recommendation - Generate IDE completion helpers
//  Consider generating synthetic properties that improve IDE autocomplete experience.
//  For example, generate User.Companion.fields object with all field names as string constants.
//  This would help when building dynamic queries or debugging field names.
//
// TODO: API Recommendation - Add validation for supported field types
//  Some field types may not work well with all database backends (e.g., complex nested generics).
//  Add validation warnings during code generation to catch unsupported patterns early.