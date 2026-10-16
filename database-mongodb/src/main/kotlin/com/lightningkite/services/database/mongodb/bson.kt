package com.lightningkite.services.database.mongodb

import com.lightningkite.services.data.TextIndex
import com.lightningkite.services.database.*
import com.lightningkite.services.database.mongodb.bson.KBson
import com.lightningkite.services.database.mongodb.bson.serializationOverrides
import com.lightningkite.services.database.mongodb.bson.toDocument
import com.mongodb.client.model.UpdateOptions
import kotlinx.datetime.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.internal.AbstractPolymorphicSerializer
import kotlinx.serialization.modules.SerializersModule
import org.bson.*
import org.bson.types.Binary
import org.bson.types.ObjectId
import java.math.BigDecimal
import java.util.*
import java.util.regex.Pattern
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import java.util.Date

internal fun documentOf(): Document {
    return Document()
}

internal fun documentOf(pair: Pair<String, Any?>): Document {
    return Document(pair.first, pair.second)
}

internal fun documentOf(vararg pairs: Pair<String, Any?>): Document {
    return Document().apply {
        for (entry in pairs) {
            this[entry.first] = entry.second
        }
    }
}

internal fun <T> Iterable<Pair<String, T>>.toDocument(): Document {
    return Document().also {
        for (entry in this) it[entry.first] = entry.second
    }
}

// TODO: This whole file is terrible

@Serializable
private data class Wrapper<T>(val value: T)

internal fun <T> KBson.stringifyAny(serializer: KSerializer<T>, obj: T): BsonValue {
    return stringify(Wrapper.serializer(serializer), Wrapper(obj))["value"]!!
}


@Suppress("UNCHECKED_CAST")
private fun <T> Condition<T>.dump(
    serializer: KSerializer<T>,
    into: Document = Document(),
    key: String?,
    atlasSearch: Boolean,
    bson: KBson,
): Document {
    when (this) {
        is Condition.Always -> {}
        is Condition.Never -> into["thisFieldWillNeverExist"] = "no never"
        is Condition.And -> {
            into["\$and"] = conditions.map { it.dump(serializer, key = key, atlasSearch = atlasSearch, bson = bson) }
        }

        is Condition.Or -> if (conditions.isEmpty()) into["thisFieldWillNeverExist"] = "no never" else into["\$or"] =
            conditions.map { it.dump(serializer, key = key, atlasSearch = atlasSearch, bson = bson) }

        is Condition.Equal -> into.sub(key)["\$eq"] = value.let { bson.stringifyAny(serializer, it) }
        is Condition.NotEqual -> into.sub(key)["\$ne"] = value.let { bson.stringifyAny(serializer, it) }
        is Condition.SetAllElements<*> -> {
            val innerSerializer = serializer.listElement()!! as KSerializer<Any?>
            val matchDoc = Document()
            (Condition.Not(condition as Condition<Any?>)).dump(
                innerSerializer,
                into = matchDoc,
                key = null,
                atlasSearch = atlasSearch,
                bson = bson
            )
            into.sub(key)["\$not"] = documentOf("\$elemMatch" to matchDoc)
            // $elemMatch never matches a null element with a condition on its fields, so a null element can't fail one.
            if (innerSerializer.descriptor.isNullable && !(condition as Condition<Any?>)(null)) into.sub(key)["\$ne"] = null
        }
        // by Claude - Atlas $vectorSearch doesn't support $elemMatch in pre-filters.
        // When atlasSearch=true, dump the inner condition directly on the key instead;
        // MongoDB scalar operators ($eq, $in, etc.) on array fields already match element-wise.
        is Condition.SetAnyElements<*> -> if (atlasSearch) {
            (condition as Condition<Any?>).dump(
                serializer.listElement()!! as KSerializer<Any?>,
                into,
                key,
                atlasSearch = atlasSearch,
                bson = bson
            )
        } else {
            into.sub(key)["\$elemMatch"] =
                (condition as Condition<Any?>).bson(serializer.listElement()!! as KSerializer<Any?>, bson = bson)
        }

        is Condition.ListAllElements<*> -> {
            val innerSerializer = serializer.listElement()!! as KSerializer<Any?>
            val matchDoc = Document()
            (Condition.Not(condition as Condition<Any?>)).dump(
                innerSerializer,
                into = matchDoc,
                key = null,
                atlasSearch = atlasSearch,
                bson = bson
            )
            into.sub(key)["\$not"] = documentOf("\$elemMatch" to matchDoc)
            // $elemMatch never matches a null element with a condition on its fields, so a null element can't fail one.
            if (innerSerializer.descriptor.isNullable && !(condition as Condition<Any?>)(null)) into.sub(key)["\$ne"] = null
        }


        is Condition.ListAnyElements<*> -> if (atlasSearch) {
            (condition as Condition<Any?>).dump(
                serializer.listElement()!! as KSerializer<Any?>,
                into,
                key,
                atlasSearch = atlasSearch,
                bson = bson
            )
        } else {
            into.sub(key)["\$elemMatch"] =
                (condition as Condition<Any?>).bson(serializer.listElement()!! as KSerializer<Any?>, bson = bson)
        }

        is Condition.Exists<*> -> into[if (key == null) this.key else "$key.${this.key}"] =
            documentOf("\$exists" to true)

        is Condition.GreaterThan -> into.sub(key)["\$gt"] = value.let { bson.stringifyAny(serializer, it) }
        is Condition.LessThan -> into.sub(key)["\$lt"] = value.let { bson.stringifyAny(serializer, it) }
        is Condition.GreaterThanOrEqual -> into.sub(key)["\$gte"] = value.let { bson.stringifyAny(serializer, it) }
        is Condition.LessThanOrEqual -> into.sub(key)["\$lte"] = value.let { bson.stringifyAny(serializer, it) }
        // Mongo's $ne, $nin, $not and an empty condition all match a null or missing value, so for those the null check
        // that IfNotNull does in memory has to be spelled out.
        is Condition.IfNotNull<*> -> {
            val nonNullSerializer = serializer.nullElement()!! as KSerializer<Any?>
            // Mongo compares an array with null element by element, so `$ne: null` would reject [1, null].
            val notNull = if (nonNullSerializer.descriptor.kind == StructureKind.LIST) documentOf("\$type" to "array")
                else documentOf("\$ne" to null)
            if (!condition.matchesNullInMongo(atlasSearch)) {
                (condition as Condition<Any?>).dump(nonNullSerializer, into, key, atlasSearch = atlasSearch, bson = bson)
            } else if (key != null) {
                into["\$and"] = listOfNotNull(
                    documentOf(key to notNull),
                    (condition as Condition<Any?>).takeUnless { it is Condition.Always }
                        ?.dump(nonNullSerializer, key = key, atlasSearch = atlasSearch, bson = bson),
                )
            } else {
                // No key means this is a list element inside $elemMatch. The operators there apply to the element itself,
                // so the check joins them, with a $ne rewritten as the equivalent $nin to make room for it.
                // Conditions on the element's fields need no check: $elemMatch never matches a null element with those.
                // $and/$or/$nor aren't operators on the element, so those forms get no check (and Mongo rejects them here).
                val inner = (condition as? Condition.NotEqual)?.let { Condition.NotInside(setOf(it.value)) } ?: condition
                val operators = (inner as Condition<Any?>).dump(nonNullSerializer, key = null, atlasSearch = atlasSearch, bson = bson)
                into.putAll(operators)
                if (operators.keys.all { it.startsWith("\$") && it !in setOf("\$and", "\$or", "\$nor") }) into.putAll(notNull)
            }
        }

        is Condition.Inside -> into.sub(key)["\$in"] = values.let { bson.stringifyAny(SetSerializer(serializer), it) }
        is Condition.NotInside -> into.sub(key)["\$nin"] =
            values.let { bson.stringifyAny(SetSerializer(serializer), it) }

        // The All/Any in the Mongo operator name must match the All/Any in the condition name:
        // `IntBitsClear` means every mask bit is clear, so it is `$bitsAllClear`, and so on.
        //
        // The mask is passed as a list of bit positions rather than as a number because Mongo rejects
        // a negative numeric bitmask, and any mask containing bit 31 is negative as a signed Int.
        is Condition.IntBitsAnyClear -> into.sub(key)["\$bitsAnyClear"] = mask.bitPositions()
        is Condition.IntBitsAnySet -> into.sub(key)["\$bitsAnySet"] = mask.bitPositions()
        is Condition.IntBitsClear -> into.sub(key)["\$bitsAllClear"] = mask.bitPositions()
        is Condition.IntBitsSet -> into.sub(key)["\$bitsAllSet"] = mask.bitPositions()
        is Condition.Not -> {
            val inner = condition.dump(serializer, key = key, atlasSearch = atlasSearch, bson = bson)
            val isOperatorDocument =
                inner.keys.all {
                    it.startsWith("$") &&
                            !it.contains("\$and") &&
                            !it.contains("\$or") &&
                            !it.contains("\$not")
                }
            if (isOperatorDocument) { // i.e. { "$not": { "$lt": 4 } }
                into["\$not"] = inner
            } else {
                into["\$nor"] = listOf(inner)
            }
        }

        // Like IfNotNull: OnKey never matches a missing key in memory, but $ne, $nin and $not do.
        is Condition.OnKey<*> -> {
            val path = if (key == null) this.key else "$key.${this.key}"
            val valueSerializer = serializer.mapValueElement() as KSerializer<Any?>
            if (!condition.matchesNullInMongo(atlasSearch)) {
                (condition as Condition<Any?>).dump(valueSerializer, into, path, atlasSearch = atlasSearch, bson = bson)
            } else {
                into["\$and"] = listOfNotNull(
                    documentOf(path to documentOf("\$exists" to true)),
                    (condition as Condition<Any?>).takeUnless { it is Condition.Always }
                        ?.dump(valueSerializer, key = path, atlasSearch = atlasSearch, bson = bson),
                )
            }
        }

        is Condition.GeoDistance -> {
            // $nearSphere with GeoJSON geometry works on both MongoDB and DocumentDB.
            // $geoWithin/$centerSphere was the old form but $centerSphere is not supported by DocumentDB.
            val geoDoc = documentOf(
                "\$geometry" to documentOf(
                    "type" to "Point",
                    "coordinates" to listOf(this.value.longitude, this.value.latitude)
                ),
                "\$maxDistance" to this.lessThanKilometers * 1000.0  // km → meters
            )
            if (this.greaterThanKilometers > 0.0) geoDoc["\$minDistance"] = this.greaterThanKilometers * 1000.0
            into.sub(key)["\$nearSphere"] = geoDoc
        }

        // Use BsonRegularExpression to embed options in the BSON regex type rather than a
        // separate $options field. DocumentDB 5.0+ rejects {$regex: "str", $options: "i"}.
        is Condition.StringContains ->
            into.sub(key)["\$regex"] = BsonRegularExpression(Regex.escape(this.value), if (this.ignoreCase) "i" else "")

        is Condition.RawStringContains ->
            into.sub(key)["\$regex"] = BsonRegularExpression(Regex.escape(this.value), if (this.ignoreCase) "i" else "")

        is Condition.RegexMatches ->
            into.sub(key)["\$regex"] = BsonRegularExpression(this.pattern, if (this.ignoreCase) "i" else "")

        is Condition.FullTextSearch -> {
            if (atlasSearch) {
                val terms = value.split(' ')
                val ser = DataClassPathSerializer(serializer)
                val paths = serializer.descriptor.annotations.filterIsInstance<TextIndex>().firstOrNull()?.fields?.map {
                    ser.fromString(it) as DataClassPath<T, String>
                }?.takeIf { it.isNotEmpty() } ?: return into
                val subs = terms.filter { !it.termShouldUseFuzzySearch() }
                    .map { term ->
                        Condition.Or(paths.map { it.mapCondition(Condition.StringContains(term, true)) })
                    }
                if (subs.isNotEmpty()) {
                    if (this.requireAllTermsPresent)
                        Condition.And(subs).dump(serializer, into, key, atlasSearch, bson = bson)
                    else
                        Condition.Or(subs).dump(serializer, into, key, atlasSearch, bson = bson)
                }
            } else into["\$text"] = documentOf(
                "\$search" to value,
                "\$caseSensitive" to false
            )
        }

        is Condition.SetSizesEquals<*> -> into.sub(key)["\$size"] = count
        is Condition.ListSizesEquals<*> -> into.sub(key)["\$size"] = count
        is Condition.OnField<*, *> -> (condition as Condition<Any?>).dump(
            this.key.serializer as KSerializer<Any?>,
            into,
            key =
                if (this.key.inline) key
                else if (key == null) this.key.name
                else "$key.${this.key.name}",
            atlasSearch = atlasSearch,
            bson = bson
        )

        // Polymorphic values are encoded flat - `{ _t: "SerialName", ...subtypeFields }` - so the type check is
        // a match on the discriminator field, and the subtype condition applies at the same key.
        is Condition.IfIsType<*, *> -> {
            if (condition is Condition.Equal) {
                // Encoding the value with the outer (polymorphic) serializer includes the discriminator, so a whole-value
                // match already implies the type check.
                (condition as Condition<Any?>).dump(serializer, into, key, atlasSearch, bson)
            } else {
                if (key == null && condition is Condition.NotEqual) throw IllegalArgumentException(
                    "Condition.IfIsType with Condition.NotEqual cannot be applied to a whole element (such as inside \$elemMatch), " +
                            "since Mongo can't combine the discriminator field check with a whole-value \$ne."
                )
                into[if (key == null) bson.configuration.classDiscriminator else "$key.${bson.configuration.classDiscriminator}"] =
                    documentOf("\$eq" to discriminator.serialName)
                if (condition is Condition.NotEqual) {
                    // The outer serializer is used so the compared value includes the discriminator, matching the stored layout.
                    (condition as Condition<Any?>).dump(serializer, into, key, atlasSearch, bson)
                } else if (condition !is Condition.Always) {
                    (condition as Condition<Any?>).dump(
                        serializer.polymorphicSubSerializer(discriminator.serialName, bson.serializersModule)
                            ?: throw IllegalArgumentException(
                                "Could not find a serializer for '${discriminator.serialName}' as a subtype of '${serializer.descriptor.serialName}'."
                            ),
                        into,
                        key,
                        atlasSearch = atlasSearch,
                        bson = bson
                    )
                }
            }
        }
    }
    return into
}

// False only where it's certain that this condition's translation can't match a null or missing value.
private fun Condition<*>.matchesNullInMongo(atlasSearch: Boolean): Boolean = when (this) {
    is Condition.Never, is Condition.GreaterThan, is Condition.LessThan, is Condition.GreaterThanOrEqual,
    is Condition.LessThanOrEqual, is Condition.StringContains, is Condition.RawStringContains, is Condition.RegexMatches,
    is Condition.IntBitsClear, is Condition.IntBitsSet, is Condition.IntBitsAnyClear, is Condition.IntBitsAnySet,
    is Condition.ListSizesEquals<*>, is Condition.SetSizesEquals<*>, is Condition.Exists<*>, is Condition.OnKey<*>,
    is Condition.IfNotNull<*>, is Condition.IfIsType<*, *>, is Condition.GeoDistance -> false
    is Condition.Equal -> value == null
    is Condition.Inside -> values.any { it == null }
    is Condition.And -> conditions.all { it.matchesNullInMongo(atlasSearch) }
    is Condition.Or -> conditions.any { it.matchesNullInMongo(atlasSearch) }
    // A missing parent makes the field missing too.
    is Condition.OnField<*, *> -> condition.matchesNullInMongo(atlasSearch)
    // $elemMatch needs an array; with atlasSearch these put the element condition straight on the field instead.
    is Condition.ListAnyElements<*> -> atlasSearch && condition.matchesNullInMongo(atlasSearch)
    is Condition.SetAnyElements<*> -> atlasSearch && condition.matchesNullInMongo(atlasSearch)
    else -> true
}

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
private fun KSerializer<*>.polymorphicSubSerializer(
    serialName: String,
    serializersModule: SerializersModule
): KSerializer<Any?>? {
    val base = if (descriptor.isNullable) nullElement() ?: return null else this
    val poly = base as? AbstractPolymorphicSerializer<*> ?: return null
    val lookup = object : AbstractDecoder() {
        override val serializersModule: SerializersModule = serializersModule
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int = CompositeDecoder.DECODE_DONE
    }
    @Suppress("UNCHECKED_CAST")
    return poly.findPolymorphicSerializerOrNull(lookup, serialName) as? KSerializer<Any?>
}

@Suppress("UNCHECKED_CAST")
private fun <T> Modification<T>.dump(
    serializer: KSerializer<T>,
    update: UpdateWithOptions = UpdateWithOptions(),
    key: String?,
    bson: KBson,
): UpdateWithOptions {
    val into = update.document
    when (this) {
        is Modification.Nothing -> TODO("Not supported")
        is Modification.Chain -> modifications.forEach { it.dump(serializer, update, key, bson = bson) }
        is Modification.Assign -> into["\$set", key] = value.let { bson.stringifyAny(serializer, it) }
        is Modification.CoerceAtLeast -> into["\$max", key] = value.let { bson.stringifyAny(serializer, it) }
        is Modification.CoerceAtMost -> into["\$min", key] = value.let { bson.stringifyAny(serializer, it) }
        is Modification.Increment -> into["\$inc", key] = by.let { bson.stringifyAny(serializer, it) }
        is Modification.Multiply -> into["\$mul", key] = by.let { bson.stringifyAny(serializer, it) }
        is Modification.AppendString -> TODO("Appending strings is not supported yet")
        is Modification.AppendRawString -> TODO("Appending raw strings is not supported yet")
        // IfNotNull and IfIsType only get here once something else does their check: the update's filter or an array
        // filter (see [bson] and [perElement]). Update operators can't.
        is Modification.IfNotNull<*> -> (modification as Modification<Any?>).dump(
            serializer.nullElement()!! as KSerializer<Any?>,
            update,
            key,
            bson = bson
        )

        is Modification.IfIsType<*, *> -> when (val inner = modification) {
            // Encoding with the outer (polymorphic) serializer keeps the discriminator on the stored value.
            is Modification.Assign -> (inner as Modification<T>).dump(serializer, update, key, bson = bson)
            else -> (inner as Modification<Any?>).dump(
                serializer.polymorphicSubSerializer(discriminator.serialName, bson.serializersModule)
                    ?: throw IllegalArgumentException(
                        "Could not find a serializer for '${discriminator.serialName}' as a subtype of '${serializer.descriptor.serialName}'."
                    ),
                update,
                key,
                bson = bson
            )
        }

        is Modification.OnField<*, *> -> (modification as Modification<Any?>).dump(
            this.key.serializer as KSerializer<Any?>,
            update,
            key =
                if (this.key.inline) key
                else if (key == null) this.key.name
                else "$key.${this.key.name}",
            bson = bson
        )

        is Modification.ListAppend<*> -> into.sub("\$push").sub(key)["\$each"] =
            items.let { bson.stringifyAny(serializer as KSerializer<List<Any?>>, it) }

        is Modification.ListRemove<*> -> into["\$pull", key] =
            (condition as Condition<Any?>).bson(serializer.listElement() as KSerializer<Any?>, bson = bson)

        is Modification.ListRemoveInstances<*> -> into["\$pullAll", key] =
            items.let { bson.stringifyAny(serializer as KSerializer<List<Any?>>, it) }

        is Modification.ListDropFirst<*> -> into["\$pop", key] = -1
        is Modification.ListDropLast<*> -> into["\$pop", key] = 1
        is Modification.ListPerElement<*> -> perElement(
            condition as Condition<Any?>,
            modification as Modification<Any?>,
            serializer.listElement() as KSerializer<Any?>,
            update,
            key,
            bson
        )

        is Modification.SetAppend<*> -> into.sub("\$addToSet").sub(key)["\$each"] =
            items.let { bson.stringifyAny(serializer as KSerializer<Set<Any?>>, it) }

        is Modification.SetRemove<*> -> into["\$pull", key] =
            (condition as Condition<Any?>).bson(serializer.listElement() as KSerializer<Any?>, bson = bson)

        is Modification.SetRemoveInstances<*> -> into["\$pullAll", key] =
            items.let { bson.stringifyAny(serializer as KSerializer<Set<Any?>>, it) }

        is Modification.SetDropFirst<*> -> into["\$pop", key] = -1
        is Modification.SetDropLast<*> -> into["\$pop", key] = 1
        is Modification.SetPerElement<*> -> perElement(
            condition as Condition<Any?>,
            modification as Modification<Any?>,
            serializer.listElement() as KSerializer<Any?>,
            update,
            key,
            bson
        )

        is Modification.Combine<*> -> map.forEach {
            into.sub("\$set")[if (key == null) it.key else "$key.${it.key}"] =
                it.value.let { bson.stringifyAny(serializer.mapValueElement() as KSerializer<Any?>, it) }
        }

        is Modification.RemoveKeys<*> -> this.fields.forEach {
            into.sub("\$unset")[if (key == null) it else "$key.${it}"] = ""
        }
    }
    return update
}

// Applying the parts in order is the same as applying the whole, and a part never changes the outcome of its own check,
// since it only writes below the checked value.
@Suppress("UNCHECKED_CAST")
private fun Modification<Any?>.checkedParts(): List<Pair<Condition<Any?>, Modification<Any?>>> = when (this) {
    is Modification.Chain -> modifications.flatMap { it.checkedParts() }
    is Modification.OnField<*, *> -> (modification as Modification<Any?>).checkedParts().map { (check, part) ->
        val field = this.key as SerializableProperty<Any?, Any?>
        (if (check is Condition.Always) check else Condition.OnField(field, check)) to Modification.OnField(field, part)
    }
    is Modification.IfNotNull<*> -> (modification as Modification<Any?>).checkedParts().map { (check, part) ->
        Condition.IfNotNull(check) to Modification.IfNotNull(part)
    }
    is Modification.IfIsType<*, *> -> (modification as Modification<Any?>).checkedParts().map { (check, part) ->
        val type = discriminator as SealedTypeDiscriminator<Any?>
        Condition.IfIsType(type, check) to Modification.IfIsType(type, part)
    }
    else -> listOf(Condition.Always to this)
}

// Update operators can't check a value before changing it, so each notNull/asType check inside an element gets its own
// array filter.
@Suppress("UNCHECKED_CAST")
private fun perElement(
    condition: Condition<Any?>,
    modification: Modification<Any?>,
    elementSerializer: KSerializer<Any?>,
    update: UpdateWithOptions,
    key: String?,
    bson: KBson,
) {
    for ((check, parts) in modification.checkedParts().groupBy({ it.first }, { it.second })) {
        val filter = Condition.And(listOf(condition, check)).simplify()
        if (filter is Condition.Always) {
            Modification.Chain(parts).dump(elementSerializer, update, "$key.$[]", bson)
        } else {
            val identifier = "f${update.options.arrayFilters?.size ?: 0}"
            update.options = update.options.arrayFilters(
                (update.options.arrayFilters ?: listOf()) +
                        filter.dump(elementSerializer, key = identifier, atlasSearch = false, bson = bson)
            )
            Modification.Chain(parts).dump(elementSerializer, update, "$key.$[$identifier]", bson)
        }
    }
}

private fun Document.sub(key: String?): Document = if (key == null) this else getOrPut(key) { Document() } as Document
private operator fun Document.set(owner: String, key: String?, value: Any?) {
    if (key == null) this[owner] = value
    else this.sub(owner)[key] = value
}

internal data class UpdateWithOptions(
    val document: Document = Document(),
    var options: UpdateOptions = UpdateOptions(),
    /** The notNull/asType checks outside lists, which the update's filter must include. */
    val check: Document? = null,
)

internal fun <T> Condition<T>.bson(serializer: KSerializer<T>, atlasSearch: Boolean = false, bson: KBson): Document =
    Document().also { dump(serializer, it, null, atlasSearch, bson) }

/**
 * Removes top-level update modifiers whose operand is empty (e.g. `{ "$set": {} }`). MongoDB silently treats
 * these as no-ops, but DocumentDB rejects them with error 9 "Modifiers operate on fields" — which happens for
 * an upsert of a model that has no fields beyond `_id` (the health-check model, for instance).
 */
internal fun Document.pruneEmptyModifiers(): Document {
    entries.filter { (key, value) ->
        key.startsWith("\$") && when (value) {
            is Document -> value.isEmpty()
            is BsonDocument -> value.isEmpty()
            else -> false
        }
    }.map { it.key }.forEach { remove(it) }
    return this
}

/**
 * Update operators can't check a value before changing it, so every notNull/asType check outside a list goes into
 * [UpdateWithOptions.check]: a row failing any of them isn't written at all. Inside a list, [perElement] uses array filters.
 */
@Suppress("UNCHECKED_CAST")
internal fun <T> Modification<T>.bson(serializer: KSerializer<T>, bson: KBson): UpdateWithOptions {
    val anySerializer = serializer as KSerializer<Any?>
    val parts = (this as Modification<Any?>).checkedParts()
    val check = Condition.And(parts.map { it.first }.distinct()).simplify()
    val update = UpdateWithOptions(check = if (check is Condition.Always) null else check.bson(anySerializer, bson = bson))
    Modification.Chain(parts.map { it.second }).dump(anySerializer, update, null, bson)
    update.document.pruneEmptyModifiers()
    return update
}

/**
 * Tries to turn this update into a single atomic upsert by adding a `$setOnInsert` of [model],
 * returning false if it can't be proven equivalent to "insert [model] as-is" (the caller then falls
 * back to findOneAndUpdate-then-insertOne).
 *
 * The dedup below has to establish that every key the update's operators write already holds the
 * same value in [model] -- otherwise Mongo's upsert-insert, which applies `$set`/`$inc` to the new
 * document too, would produce something other than [model].
 *
 * That check is only sound for **top-level** operator keys. A modification on a nested field
 * produces a dotted path (`"embedded.value2"`), and the `$setOnInsert` source is a nested [Document]
 * whose `get` does no path traversal -- so every dotted lookup misses. Worse, even a *successful*
 * nested dedup would be wrong: removing a leaf leaves the parent object in `$setOnInsert`, and
 * MongoDB rejects an update whose operators write overlapping paths ("would create a conflict at
 * 'embedded'"). So a dotted key means we cannot prove equivalence, and false is the correct answer.
 *
 * Bailing out early also fixes two defects the old missed-lookup behaviour caused: `$inc` on a
 * nested field force-cast the missing value and threw NullPointerException, and a dotted key in
 * [restrict] silently read as "absent from the model" and let an unprovable upsert through.
 */
internal fun <T> UpdateWithOptions.upsert(model: T, serializer: KSerializer<T>, bson: KBson): Boolean {
    // With a check, Mongo would insert where a row matched but failed the check.
    if (check != null) return false
    val set: Document? = (document["\$set"] as? Document) ?: (document["\$set"] as? BsonDocument)?.toDocument()
    val inc = (document["\$inc"] as? Document) ?: (document["\$inc"] as? BsonDocument)?.toDocument()
    val restrict = document.entries.asSequence()
        .filter { it.key != "\$set" && it.key != "\$inc" }
        .map { it.value }
        .filterIsInstance<Document>()
        .flatMap { it.keys }
        .toSet()
    // See the KDoc: dotted paths can't be resolved against the nested $setOnInsert document, and
    // deduping one wouldn't be safe even if they could.
    if (set?.keys.orEmpty().any { '.' in it } || inc?.keys.orEmpty().any { '.' in it } ||
        restrict.any { '.' in it }
    ) return false
    document["\$setOnInsert"] = bson.stringify(serializer, model).toDocument().also {
        set?.keys?.forEach { k ->
            if (it[k] == set[k]) it.remove(k)
            else {
                return false
            }
        }
        inc?.keys?.forEach { k ->
            if ((it[k] as Number).toDouble() == (inc[k] as BsonNumber).doubleValue()) it.remove(k)
            else {
                return false
            }
        }
        restrict.forEach { k ->
            if (it.containsKey(k)) return false
        }
    }
    // Deduping above can empty out $setOnInsert (e.g. upserting an id-only model); drop any modifier that
    // became empty so DocumentDB doesn't reject the command with "Modifiers operate on fields".
    document.pruneEmptyModifiers()
    options = options.upsert(true)
    return true
}

@OptIn(ExperimentalSerializationApi::class)
internal fun SerialDescriptor.bsonType(module: SerializersModule): BsonType = when {
    isInline -> getElementDescriptor(0).bsonType(module)
    else -> serializationOverrides[this]?.bsonType ?: when (kind) {
        SerialKind.ENUM -> BsonType.STRING
        SerialKind.CONTEXTUAL -> when (this.capturedKClass) {
            ObjectId::class -> BsonType.OBJECT_ID
            BigDecimal::class -> BsonType.DECIMAL128
            ByteArray::class -> BsonType.BINARY
            Date::class -> BsonType.DATE_TIME
            Calendar::class -> BsonType.DATE_TIME
            GregorianCalendar::class -> BsonType.DATE_TIME
            kotlin.time.Instant::class -> BsonType.DATE_TIME
            LocalDate::class -> BsonType.DATE_TIME
            LocalDateTime::class -> BsonType.DATE_TIME
            LocalTime::class -> BsonType.DATE_TIME
            BsonTimestamp::class -> BsonType.DATE_TIME
            Locale::class -> BsonType.STRING
            Binary::class -> BsonType.BINARY
            Pattern::class -> BsonType.DOCUMENT
            Regex::class -> BsonType.DOCUMENT
            UUID::class -> BsonType.BINARY
            Uuid::class -> BsonType.BINARY
            else -> module.getContextualDescriptor(this)!!.bsonType(module)
        }

        PrimitiveKind.BOOLEAN -> BsonType.BOOLEAN
        PrimitiveKind.BYTE -> BsonType.INT32
        PrimitiveKind.CHAR -> BsonType.SYMBOL
        PrimitiveKind.SHORT -> BsonType.INT32
        PrimitiveKind.INT -> BsonType.INT32
        PrimitiveKind.LONG -> BsonType.INT64
        PrimitiveKind.FLOAT -> BsonType.DOUBLE
        PrimitiveKind.DOUBLE -> BsonType.DOUBLE
        PrimitiveKind.STRING -> BsonType.STRING
        StructureKind.CLASS -> BsonType.DOCUMENT
        StructureKind.LIST -> BsonType.ARRAY
        StructureKind.MAP -> BsonType.DOCUMENT
        StructureKind.OBJECT -> BsonType.STRING
        PolymorphicKind.SEALED -> TODO()
        PolymorphicKind.OPEN -> TODO()
    }
}


/** The indices of the set bits, which is how Mongo's `$bits*` operators accept a mask unambiguously. */
private fun Int.bitPositions(): List<Int> = (0 until Int.SIZE_BITS).filter { this and (1 shl it) != 0 }
