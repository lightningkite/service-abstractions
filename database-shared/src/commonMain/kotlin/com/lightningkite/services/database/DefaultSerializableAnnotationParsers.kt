package com.lightningkite.services.database

import com.lightningkite.services.data.AdminHidden
import com.lightningkite.services.data.AdminSearchFields
import com.lightningkite.services.data.AdminTableColumns
import com.lightningkite.services.data.AdminTitleFields
import com.lightningkite.services.data.AdminViewOnly
import com.lightningkite.services.data.Denormalized
import com.lightningkite.services.data.Description
import com.lightningkite.services.data.DisplayName
import com.lightningkite.services.data.DoesNotNeedLabel
import com.lightningkite.services.data.ExpectedPattern
import com.lightningkite.services.data.FloatRange
import com.lightningkite.services.data.Group
import com.lightningkite.services.data.Hint
import com.lightningkite.services.data.Importance
import com.lightningkite.services.data.Index
import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.data.IntegerRange
import com.lightningkite.services.data.JsonSchemaFormat
import com.lightningkite.services.data.MaxLength
import com.lightningkite.services.data.MaxSize
import com.lightningkite.services.data.MimeType
import com.lightningkite.services.data.Multiline
import com.lightningkite.services.data.MultipleReferences
import com.lightningkite.services.data.NaturalSort
import com.lightningkite.services.data.References
import com.lightningkite.services.data.Sentence
import com.lightningkite.services.data.TextIndex
import com.lightningkite.services.data.UiWidget

/**
 * Registers [SerializableAnnotation.parser] entries for the built-in `@SerialInfo` annotations
 * shipped with `data-shared` and `database-shared`.
 *
 * Previously these registrations were emitted into a `prepareModels<Module>.kt` file by the KSP
 * `database-processor`. That generation step was removed in commit 4be314e9 ("Field generation and
 * serializable properties without prepareModels"), leaving non-JVM targets with no parsers — JVM
 * still works because [SerializableAnnotation.parseOrNull] falls back to reflection.
 *
 * Touched (via a private property) from [SerializableAnnotation]'s companion to ensure registration
 * happens before the first lookup.
 */
internal object DefaultSerializableAnnotationParsers {
    init {
        SerializableAnnotation.parser<NaturalSort>("com.lightningkite.services.data.NaturalSort") { SerializableAnnotation("com.lightningkite.services.data.NaturalSort", values = mapOf("fields" to SerializableAnnotationValue(it.fields))) }
        SerializableAnnotation.parser<AdminSearchFields>("com.lightningkite.services.data.AdminSearchFields") { SerializableAnnotation("com.lightningkite.services.data.AdminSearchFields", values = mapOf("fields" to SerializableAnnotationValue(it.fields))) }
        SerializableAnnotation.parser<AdminTableColumns>("com.lightningkite.services.data.AdminTableColumns") { SerializableAnnotation("com.lightningkite.services.data.AdminTableColumns", values = mapOf("fields" to SerializableAnnotationValue(it.fields))) }
        SerializableAnnotation.parser<AdminTitleFields>("com.lightningkite.services.data.AdminTitleFields") { SerializableAnnotation("com.lightningkite.services.data.AdminTitleFields", values = mapOf("fields" to SerializableAnnotationValue(it.fields))) }
        SerializableAnnotation.parser<AdminHidden>("com.lightningkite.services.data.AdminHidden") { SerializableAnnotation("com.lightningkite.services.data.AdminHidden", values = mapOf()) }
        SerializableAnnotation.parser<AdminViewOnly>("com.lightningkite.services.data.AdminViewOnly") { SerializableAnnotation("com.lightningkite.services.data.AdminViewOnly", values = mapOf()) }
        SerializableAnnotation.parser<Multiline>("com.lightningkite.services.data.Multiline") { SerializableAnnotation("com.lightningkite.services.data.Multiline", values = mapOf()) }
        SerializableAnnotation.parser<UiWidget>("com.lightningkite.services.data.UiWidget") { SerializableAnnotation("com.lightningkite.services.data.UiWidget", values = mapOf("type" to SerializableAnnotationValue(it.type))) }
        SerializableAnnotation.parser<JsonSchemaFormat>("com.lightningkite.services.data.JsonSchemaFormat") { SerializableAnnotation("com.lightningkite.services.data.JsonSchemaFormat", values = mapOf("format" to SerializableAnnotationValue(it.format))) }
        SerializableAnnotation.parser<IntegerRange>("com.lightningkite.services.data.IntegerRange") { SerializableAnnotation("com.lightningkite.services.data.IntegerRange", values = mapOf("min" to SerializableAnnotationValue(it.min), "max" to SerializableAnnotationValue(it.max))) }
        SerializableAnnotation.parser<FloatRange>("com.lightningkite.services.data.FloatRange") { SerializableAnnotation("com.lightningkite.services.data.FloatRange", values = mapOf("min" to SerializableAnnotationValue(it.min), "max" to SerializableAnnotationValue(it.max))) }
        SerializableAnnotation.parser<ExpectedPattern>("com.lightningkite.services.data.ExpectedPattern") { SerializableAnnotation("com.lightningkite.services.data.ExpectedPattern", values = mapOf("pattern" to SerializableAnnotationValue(it.pattern))) }
        SerializableAnnotation.parser<DisplayName>("com.lightningkite.services.data.DisplayName") { SerializableAnnotation("com.lightningkite.services.data.DisplayName", values = mapOf("text" to SerializableAnnotationValue(it.text))) }
        SerializableAnnotation.parser<Hint>("com.lightningkite.services.data.Hint") { SerializableAnnotation("com.lightningkite.services.data.Hint", values = mapOf("text" to SerializableAnnotationValue(it.text))) }
        SerializableAnnotation.parser<MimeType>("com.lightningkite.services.data.MimeType") { SerializableAnnotation("com.lightningkite.services.data.MimeType", values = mapOf("types" to SerializableAnnotationValue(it.types), "maxSize" to SerializableAnnotationValue(it.maxSize))) }
        SerializableAnnotation.parser<MaxLength>("com.lightningkite.services.data.MaxLength") { SerializableAnnotation("com.lightningkite.services.data.MaxLength", values = mapOf("size" to SerializableAnnotationValue(it.size), "average" to SerializableAnnotationValue(it.average))) }
        SerializableAnnotation.parser<MaxSize>("com.lightningkite.services.data.MaxSize") { SerializableAnnotation("com.lightningkite.services.data.MaxSize", values = mapOf("size" to SerializableAnnotationValue(it.size), "average" to SerializableAnnotationValue(it.average))) }
        SerializableAnnotation.parser<Denormalized>("com.lightningkite.services.data.Denormalized") { SerializableAnnotation("com.lightningkite.services.data.Denormalized", values = mapOf("calculationId" to SerializableAnnotationValue(it.calculationId))) }
        SerializableAnnotation.parser<Description>("com.lightningkite.services.data.Description") { SerializableAnnotation("com.lightningkite.services.data.Description", values = mapOf("text" to SerializableAnnotationValue(it.text))) }
        SerializableAnnotation.parser<References>("com.lightningkite.services.data.References") { SerializableAnnotation("com.lightningkite.services.data.References", values = mapOf("references" to SerializableAnnotationValue(it.references), "reverseName" to SerializableAnnotationValue(it.reverseName))) }
        SerializableAnnotation.parser<MultipleReferences>("com.lightningkite.services.data.MultipleReferences") { SerializableAnnotation("com.lightningkite.services.data.MultipleReferences", values = mapOf("references" to SerializableAnnotationValue(it.references), "reverseName" to SerializableAnnotationValue(it.reverseName))) }
        SerializableAnnotation.parser<DoesNotNeedLabel>("com.lightningkite.services.data.DoesNotNeedLabel") { SerializableAnnotation("com.lightningkite.services.data.DoesNotNeedLabel", values = mapOf()) }
        SerializableAnnotation.parser<Group>("com.lightningkite.services.data.Group") { SerializableAnnotation("com.lightningkite.services.data.Group", values = mapOf("name" to SerializableAnnotationValue(it.name))) }
        SerializableAnnotation.parser<Sentence>("com.lightningkite.services.data.Sentence") { SerializableAnnotation("com.lightningkite.services.data.Sentence", values = mapOf("text" to SerializableAnnotationValue(it.text))) }
        SerializableAnnotation.parser<Importance>("com.lightningkite.services.data.Importance") { SerializableAnnotation("com.lightningkite.services.data.Importance", values = mapOf("size" to SerializableAnnotationValue(it.size))) }
        SerializableAnnotation.parser<Index>("com.lightningkite.services.data.Index") { SerializableAnnotation("com.lightningkite.services.data.Index", values = mapOf("unique" to SerializableAnnotationValue(it.unique), "name" to SerializableAnnotationValue(it.name))) }
        SerializableAnnotation.parser<IndexSet>("com.lightningkite.services.data.IndexSet") { SerializableAnnotation("com.lightningkite.services.data.IndexSet", values = mapOf("fields" to SerializableAnnotationValue(it.fields), "unique" to SerializableAnnotationValue(it.unique), "name" to SerializableAnnotationValue(it.name))) }
        SerializableAnnotation.parser<TextIndex>("com.lightningkite.services.data.TextIndex") { SerializableAnnotation("com.lightningkite.services.data.TextIndex", values = mapOf("fields" to SerializableAnnotationValue(it.fields))) }
        SerializableAnnotation.parser<VectorIndex>("com.lightningkite.services.database.VectorIndex") { SerializableAnnotation("com.lightningkite.services.database.VectorIndex", values = mapOf("dimensions" to SerializableAnnotationValue(it.dimensions), "metric" to SerializableAnnotationValue(it.metric), "sparse" to SerializableAnnotationValue(it.sparse), "indexType" to SerializableAnnotationValue(it.indexType))) }
    }
}
