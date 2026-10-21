package com.lightningkite.services.database.processor

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import java.util.Locale.getDefault

/**
 * KSP (Kotlin Symbol Processor) that generates type-safe optic field accessors.
 *
 * This processor scans for classes annotated with @Queryable or @GenerateDataClassPaths
 * and generates extension properties that enable type-safe field references in database queries.
 *
 * ## What It Generates
 *
 * For a model like:
 * ```kotlin
 * @Queryable
 * @Serializable
 * data class User(val name: String, val age: Int)
 * ```
 *
 * It generates:
 * ```kotlin
 * val User.Companion.name: Field<User, String>
 * val User.Companion.age: Field<User, Int>
 * val <ROOT> Optic<ROOT, User>.name: Optic<ROOT, String>
 * val <ROOT> Lens<ROOT, User>.name: Lens<ROOT, String>
 * (and the same for age)
 * ```
 *
 * This enables queries like: `User.age gte 18`
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
 * @see com.lightningkite.services.data.Queryable
 */
class TableGenerator(
    val codeGenerator: CodeGenerator,
    val logger: KSPLogger,
) : CommonSymbolProcessor2(codeGenerator, "lightningdb", 24) {
    fun KSClassDeclaration.needsDcp(): Boolean =
        annotation("Queryable") != null || annotation("GenerateDataClassPaths") != null

    // Claude: @Queryable is @MetaSerializable, but KSP only sees annotations written in source, so check for it too.
    private fun KSClassDeclaration.isSerializable(): Boolean =
        annotation("Queryable") != null || annotation("Serializable", "kotlinx.serialization") != null

    override fun interestedIn(resolver: Resolver): Set<KSFile> {
        return resolver.getAllFiles()
            .filter {
                it.declarations
                    .filterIsInstance<KSClassDeclaration>()
                    .flattenNestedDeclarations()
                    .any { it.needsDcp() }
            }
            .toSet()
    }

    private fun Sequence<KSClassDeclaration>.flattenNestedDeclarations(): Sequence<KSClassDeclaration> = sequence {
        suspend fun SequenceScope<KSClassDeclaration>.yieldDFS(classes: Sequence<KSClassDeclaration>) {
            for (declaration in classes) {
                yield(declaration)
                yieldDFS(declaration.declarations.filterIsInstance<KSClassDeclaration>())
            }
        }
        yieldDFS(this@flattenNestedDeclarations)
    }

    @OptIn(KspExperimental::class)
    override fun process2(resolver: Resolver, files: Set<KSFile>) {
        if (files.isEmpty()) return
        files
            .asSequence()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .flattenNestedDeclarations()
            .filter { it.needsDcp() }
            .groupBy { it.packageName.asString() }
            .forEach { (packageName, classes) ->
                val files = classes.asSequence().mapNotNull { it.containingFile }.distinct().sortedBy { it.fileName }
                createNewFile(
                    dependencies = Dependencies(false, *files.toList().toTypedArray()),
                    packageName = packageName,
                    fileName = "ModelFields${classes.mapTo(HashSet()) { it.simpleName.asString() }.hashCode()}"
                ).use { out ->
                    TabAppendable(out).writeModelFields(packageName, files, classes)
                }
            }

        logger.info("Complete.")
    }

    private fun TabAppendable.writeModelFields(
        packageName: String,
        files: Sequence<KSFile>,
        classes: List<KSClassDeclaration>
    ) {
        appendLine("""// Automatically generated from classes ${classes.joinToString { it.simpleName.asString() }}. Do not modify!""")
        appendLine("""@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)""")
        appendLine("""@file:Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER", "UnusedImport")""")
        appendLine()
        if (packageName.isNotEmpty()) appendLine("package ${packageName}")
        appendLine()
        try {
            files
                .flatMap { it.imports }
                .plus(
                    listOf(
                        "com.lightningkite.services.database.*",
                        "com.lightningkite.services.database.optics.*",
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
            when (declaration.classKind) {
                ClassKind.INTERFACE -> {
                    if (!declaration.modifiers.contains(Modifier.SEALED)) throw IllegalArgumentException("@Queryable can only be applied to classes and sealed interfaces")
                    appendLine("// ${declaration.safeLocalReference()}")
                    writeSealedFields(declaration)
                    appendLine()
                }

                ClassKind.CLASS -> {
                    appendLine("// ${declaration.safeLocalReference()}")
                    if (declaration.modifiers.contains(Modifier.SEALED)) writeSealedFields(declaration)
                    else writeClassFields(declaration)
                    appendLine()
                }

                ClassKind.ENUM_CLASS -> {}
                ClassKind.OBJECT -> {}
                else -> throw IllegalArgumentException("@Queryable applied to invalid type: ${declaration.classKind}")
            }
        }
    }

    private fun TabAppendable.writeSealedFields(declaration: KSClassDeclaration) {
        // Outside the try below, which would otherwise turn the failure into a comment in the generated file.
        validateVariantShortNames(declaration)
        try {
            val classReference: String = declaration.safeLocalReference()
            val simpleName: String = classReference.replace('.', '_')

            if (declaration.typeParameters.isNotEmpty()) {
                appendLine("// Skipped $classReference: generic sealed types are not supported")
            } else {
                appendPathDeprecation("Lens<$classReference>()", "com.lightningkite.services.database.optics.Lens")
                appendLine("public val $classReference.Companion.path: IdentityLens<$classReference> get() = IdentityLens($classReference.serializer())")
                declaration.getSealedSubclasses()
                    .filter { it.needsDcp() && it.isSerializable() }
                    .forEach { variant ->
                        val variantReference = variant.safeLocalReference()
                        val propName = "as${variant.simpleName.asString()}"
                        if (variant.typeParameters.isNotEmpty()) {
                            appendLine("// Skipped $classReference.$propName: generic sealed variants are not supported")
                        } else {
                            appendLine("@get:JvmName(\"path${simpleName}_$propName\") public val <ROOT> Optic<ROOT, $classReference>.$propName: Optic<ROOT, $variantReference> get() = this.asType($variantReference.serializer())")
                        }
                    }
            }
        } catch (e: Exception) {
            appendLine("/*" + e.stackTraceToString() + "*/")
        }
    }

    private fun KSClassDeclaration.serialName(): String =
        annotation("SerialName", "kotlinx.serialization")?.arguments?.firstOrNull()?.value as? String
            ?: qualifiedName!!.asString()

    /** The variants kotlinx.serialization lists for this sealed type: serializable subclasses, with nested sealed types flattened. */
    private fun KSClassDeclaration.sealedOptions(): Sequence<KSClassDeclaration> = getSealedSubclasses().flatMap {
        when {
            Modifier.SEALED in it.modifiers -> it.sealedOptions()
            Modifier.ABSTRACT in it.modifiers -> emptySequence()
            it.isSerializable() -> sequenceOf(it)
            else -> emptySequence()
        }
    }

    /**
     * Fails the build when a variant's short name - its serial name relative to the sealed type's, as computed by
     * `variantShortName` in database-shared - is shared with another variant or matches another variant's full
     * serial name. Either would break JSON decoding for the whole sealed type and make path strings resolve to the
     * wrong variant.
     */
    private fun validateVariantShortNames(declaration: KSClassDeclaration) {
        val supertypeSerialName = declaration.serialName()
        val serialNames = declaration.sealedOptions().distinct().associateWith { it.serialName() }
        val shortNames = serialNames
            .mapValues { (_, serialName) ->
                serialName.removePrefix("$supertypeSerialName.").takeIf { it != serialName }
            }
        val problems = ArrayList<String>()

        shortNames.entries
            .filter { it.value != null }
            .groupBy({ it.value!! }, { it.key })
            .filterValues { it.size > 1 }
            .forEach { (shortName, sharing) ->
                problems += "'$shortName' is the short name of ${sharing.joinToString(" and ") { it.qualifiedName!!.asString() }}."
            }
        for ((variant, shortName) in shortNames) {
            if (shortName == null) continue
            val matching =
                serialNames.entries.firstOrNull { (other, serialName) -> other != variant && serialName == shortName }
            if (matching != null) {
                problems += "'$shortName' is the short name of ${variant.qualifiedName!!.asString()} and the serial name of ${matching.key.qualifiedName!!.asString()}."
            }
        }

        if (problems.isNotEmpty()) {
            val message =
                "Conflicting variant short names in sealed type ${declaration.qualifiedName!!.asString()}:\n" +
                        problems.joinToString("\n") { "  - $it" } +
                        "\nChange the @SerialName of one of the conflicting variants so the names are distinct."
            logger.error(message, declaration)
            throw IllegalStateException(message)
        }
    }

    private fun TabAppendable.writeClassFields(declaration: KSClassDeclaration) = try {
        val classReference: String = declaration.safeLocalReference()
        val fields = declaration.fields()
        val typeReference: String =
            declaration.safeLocalReference() + (declaration.typeParameters.takeUnless { it.isEmpty() }
                ?.joinToString(", ", "<", ">") { it.name.asString() } ?: "")
        val simpleName: String = classReference.replace('.', '_')

        fun appendInlinePropertyAnnotation() {
            if (declaration.modifiers.contains(Modifier.VALUE)) appendLine("@InlineProperty")
        }

        if (declaration.typeParameters.isNotEmpty()) {
            appendPathDeprecation("Lens<$typeReference>()", "com.lightningkite.services.database.optics.Lens")
            appendLine(
                "public inline fun <${
                    declaration.typeParameters.joinToString(", ") {
                        "reified " + it.name.asString() + ": " + (it.bounds.firstOrNull()
                            ?.toKotlin() ?: "Any?")
                    }
                }> $classReference.Companion.path(): IdentityLens<$typeReference> = IdentityLens(com.lightningkite.services.database.serializerOrContextual<$typeReference>())"
            )

            fun appendGenericCompanionHelper(name: String) {
                +"public fun"
                +declaration.typeParameters.joinToString(
                    ", ",
                    prefix = " <",
                    postfix = "> "
                ) { param ->
                    param.name.asString() + (param.bounds.firstOrNull()?.toKotlin()
                        ?.let { ": $it" } ?: "")
                }
                +"$classReference.Companion.$name("
                +declaration.typeParameters.joinToString(", ") { param ->
                    "${
                        param.name.asString().lowercase()
                    }: KSerializer<${param.name.asString()}>"
                }
                +"): "
            }

            appendPathDeprecation(
                "IdentityLens($classReference.serializer(${declaration.typeParameters.joinToString { it.name.asString().lowercase() }}))",
                "com.lightningkite.services.database.optics.IdentityLens"
            )
            appendGenericCompanionHelper("path")
            +"IdentityLens<$typeReference>"
            +" = "
            +"IdentityLens($classReference.Companion.serializer("
            +declaration.typeParameters.joinToString { it.name.asString().lowercase() }
            +"))"
            appendLine()

            appendGenericCompanionHelper("fields")
            +"Array<Field<$typeReference, *>> {\n"
            +"  val serializer = $classReference.Companion.serializer("
                +declaration.typeParameters.joinToString { it.name.asString().lowercase() }
                +") as GeneratedSerializer<$typeReference>\n"
            +"  return Array(${fields.size}) { index -> Field.Generated<$typeReference, Any?>(serializer, index) }\n"
            +"}"
            appendLine()

            for ((index, field) in fields.withIndex()) {
                val propName = field.name.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString()
                }
                val serPropName = "field$propName"

                val prefix = declaration.safeLocalReference().camelCase().replace('.', '_')

                appendInlinePropertyAnnotation()
                appendLine(
                    "@get:JvmName(\"${prefix}_field_$propName\") public val <${
                        declaration.typeParameters.joinToString(", ") {
                            it.name.asString() + ": " + (it.bounds.firstOrNull()
                                ?.toKotlin() ?: "Any?")
                        }
                    }> KSerializer<${typeReference}>.$serPropName: Field<$typeReference, ${field.kotlinType.toKotlin()}> get() = Field.Generated(this as GeneratedSerializer<$typeReference>, $index)"
                )
                // Claude: a Lens overload too, so a path of fields stays statically a Lens.
                for ((optic, jvmName) in listOf("Optic" to "path", "Lens" to "lens")) {
                    appendInlinePropertyAnnotation()
                    appendLine(
                        "@get:JvmName(\"${prefix}_${jvmName}_$propName\") public val <ROOT, ${
                            declaration.typeParameters.joinToString(", ") {
                                it.name.asString() + ": " + (it.bounds.firstOrNull()
                                    ?.toKotlin() ?: "Any?")
                            }
                        }> $optic<ROOT, $typeReference>.${field.name}: $optic<ROOT, ${field.kotlinType.toKotlin()}> get() = this + this.serializer.$serPropName"
                    )
                }
            }
        } else {
            appendPathDeprecation("Lens<$typeReference>()", "com.lightningkite.services.database.optics.Lens")
            appendLine("public val $typeReference.Companion.path: IdentityLens<$typeReference> get() = IdentityLens($typeReference.serializer())")
            // Claude: a model field named `fields` takes this name instead.
            if (fields.none { it.name == "fields" })
                appendLine("public val ${typeReference}.Companion.fields: Array<Field<$typeReference, *>> get() = serializer().fields!!")
            for ((index, field) in fields.withIndex()) {
                appendInlinePropertyAnnotation()
                appendLine("public val ${typeReference}.Companion.${field.name}: Field<$typeReference, ${field.kotlinType.toKotlin()}> get() = serializer().fields!![$index] as Field<$typeReference, ${field.kotlinType.toKotlin()}>")
                val serPropName = "${simpleName}_${field.name}"
                // Claude: a Lens overload too, so a path of fields stays statically a Lens.
                for ((optic, jvmName) in listOf("Optic" to "path", "Lens" to "lens")) {
                    appendInlinePropertyAnnotation()
                    appendLine("@get:JvmName(\"$jvmName$serPropName\") public val <ROOT> $optic<ROOT, $typeReference>.${field.name}: $optic<ROOT, ${field.kotlinType.toKotlin()}> get() = this + ${typeReference}.${field.name}")
                }
            }
        }
    } catch (e: Exception) {
        appendLine("/*" + e.stackTraceToString() + "*/")
    }

    private fun TabAppendable.appendPathDeprecation(replacement: String, import: String) {
        appendLine(
            "@Deprecated(\"Fields can be used as paths directly now, so you probably don't need this. " +
                "If you need a lens on the type itself, use Lens<T>().\", ReplaceWith(\"$replacement\", \"$import\"))"
        )
    }
}

context(appendable: TabAppendable)
private operator fun String.unaryPlus() = appendable.append(this)

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