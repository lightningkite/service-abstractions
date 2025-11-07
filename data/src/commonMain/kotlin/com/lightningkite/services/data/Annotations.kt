@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlin.reflect.KClass

/**
 * Data model annotations for validation, admin UI generation, and database indexing.
 *
 * This file contains annotations used to:
 * - Define validation rules ([MaxLength], [IntegerRange], [FloatRange], [ExpectedPattern])
 * - Configure admin UI behavior ([AdminSearchFields], [AdminTableColumns], [AdminHidden], etc.)
 * - Specify database indexes ([Index], [IndexSet], [TextIndex])
 * - Add metadata ([DisplayName], [Description], [Hint])
 * - Define relationships ([References], [MultipleReferences])
 *
 * These annotations are processed by:
 * - `database-processor` KSP plugin for code generation
 * - Validation framework in validate.kt
 * - Admin UI generators
 * - Database schema generators
 */

/**
 * Marks that the return value of this function should not be ignored.
 *
 * Used for static analysis tools to warn when important return values are discarded.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class CheckReturnValue

/**
 * Specifies the default sorting order for query results.
 *
 * Field names can be prefixed with:
 * - `-` for descending order
 * - `~` for case-insensitive sorting (text fields only)
 *
 * ## Example
 * ```kotlin
 * @NaturalSort(["-createdAt", "~name"])  // Sort by createdAt descending, then name ascending (case-insensitive)
 * data class Article(val createdAt: Instant, val name: String)
 * ```
 *
 * @param fields Array of field names with optional prefixes
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class NaturalSort(val fields: Array<String>)

/**
 * Specifies which fields should be searchable via text search in admin interfaces.
 *
 * @param fields Array of field names to include in text search
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class AdminSearchFields(val fields: Array<String>)

/**
 * Specifies which fields should appear as columns in admin list views.
 *
 * @param fields Array of field names to show as columns
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class AdminTableColumns(val fields: Array<String>)

/**
 * Specifies which fields should be used to generate display titles in admin views.
 *
 * Fields will be concatenated to create a human-readable title.
 *
 * @param fields Array of field names to use for the title
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class AdminTitleFields(val fields: Array<String>)

/**
 * Hides this field from admin forms (both create and edit).
 *
 * Use for computed fields, internal IDs, or fields that shouldn't be user-editable.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class AdminHidden()

/**
 * Makes this field read-only in admin forms.
 *
 * The field will be visible but not editable.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class AdminViewOnly()

/**
 * Multiline widget in admin.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Multiline()

/**
 * Set widget in admin using RJSF.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class UiWidget(val type: String)


/**
 * Format, passed onto schema
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class JsonSchemaFormat(val format: String)

/**
 * Minimum and Maximum values using whole numbers
 *
 * Only works when [SerialKind] is any of
 * [PrimitiveKind.BYTE], [PrimitiveKind.SHORT], [PrimitiveKind.INT], [PrimitiveKind.LONG]
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class IntegerRange(val min: Long, val max: Long)

/**
 * Minimum and Maximum values using floating point numbers
 *
 * Only works when [SerialKind] is [PrimitiveKind.FLOAT] or [PrimitiveKind.DOUBLE]
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class FloatRange(val min: Double, val max: Double)

/**
 * [pattern] to use on this property
 *
 * Only works when [SerialKind] is [PrimitiveKind.STRING]
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class ExpectedPattern(val pattern: String)

/**
 * A display name of the item in question.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class DisplayName(val text: String)

/**
 * Hint for what should be placed here
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Hint(val text: String)

/**
 * Which mime types are valid
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class MimeType(vararg val types: String, val maxSize: Long = Long.MAX_VALUE)

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class MaxLength(val size: Int, val average: Int = -1)

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class MaxSize(val size: Int, val average: Int = -1)

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Denormalized(val calculationId: String = "")

/**
 * A description of the item in question.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Description(val text: String)

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class GenerateDataClassPaths


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class References(
    val references: KClass<*>,
    val reverseName: String = ""
)


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class MultipleReferences(
    val references: KClass<*>,
    val reverseName: String = ""
)

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class DoesNotNeedLabel

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Group(val name: String)

/**
 * [text] should be formatted as "_ in stock"
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Sentence(val text: String)

/**
 * An integer indicating how important this is.
 * Corresponds to header sizes.
 * 1-6 represent H1-H6
 * 7 represents text
 * 8 represents subtext
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Importance(val size: Int)


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Index(val unique: Boolean = false, val name:String = "")


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
@Repeatable
annotation class IndexSet(val fields: Array<String>, val unique: Boolean = false, val name:String = "")


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class TextIndex(val fields: Array<String>)


@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.TYPEALIAS
)
public annotation class ExperimentalLightningServer(val explanation: String)
