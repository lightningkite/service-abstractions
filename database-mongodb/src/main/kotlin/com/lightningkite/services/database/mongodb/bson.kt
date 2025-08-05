package com.lightningkite.services.database.mongodb

import com.github.jershell.kbson.*
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.TextIndex
import com.lightningkite.services.database.termShouldUseFuzzySearch
import com.lightningkite.services.SettingContext
import com.lightningkite.services.database.DataClassPath
import com.lightningkite.services.database.DataClassPathSerializer
import com.lightningkite.services.database.listElement
import com.lightningkite.services.database.mapValueElement
import com.lightningkite.services.database.nullElement
import com.mongodb.client.model.UpdateOptions
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.*
import org.bson.BsonDocument
import org.bson.BsonNumber
import org.bson.BsonTimestamp
import org.bson.BsonType
import org.bson.BsonValue
import org.bson.Document
import org.bson.types.Binary
import org.bson.types.ObjectId
import java.math.BigDecimal
import kotlinx.datetime.*
import kotlinx.serialization.modules.SerializersModule
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

public fun documentOf(): Document {
    return Document()
}
public fun documentOf(pair: Pair<String, Any?>): Document {
    return Document(pair.first, pair.second)
}
public fun documentOf(vararg pairs: Pair<String, Any?>): Document {
    return Document().apply {
        for(entry in pairs) {
            this[entry.first] = entry.second
        }
    }
}

// TODO: This whole file is terrible

@Serializable private data class Wrapper<T>(val value: T)
public fun <T> KBson.stringifyAny(serializer: KSerializer<T>, obj: T): BsonValue {
    return stringify(Wrapper.serializer(serializer), Wrapper(obj))["value"]!!
}


@Suppress("UNCHECKED_CAST")
private fun <T> Condition<T>.dump(serializer: KSerializer<T>, into: Document = Document(), key: String?, atlasSearch: Boolean, context: SettingContext): Document {
    val bson = KBson(context.serializersModule, Configuration())

    when (this) {
        is Condition.Always -> {}
        is Condition.Never -> into["thisFieldWillNeverExist"] = "no never"
        is Condition.And -> {
            into["\$and"] = conditions.map { it.dump(serializer, key = key, atlasSearch = atlasSearch, context = context)  }
        }
        is Condition.Or -> if(conditions.isEmpty()) into["thisFieldWillNeverExist"] = "no never" else into["\$or"] = conditions.map { it.dump(serializer, key = key, atlasSearch = atlasSearch, context = context)  }
        is Condition.Equal -> into.sub(key)["\$eq"] = value.let { bson.stringifyAny(serializer, it) }
        is Condition.NotEqual -> into.sub(key)["\$ne"] = value.let { bson.stringifyAny(serializer, it) }
        is Condition.SetAllElements<*> -> (condition as Condition<Any?>).dump(serializer.listElement()!! as KSerializer<Any?>, into.sub(key).sub("\$not").sub("\$elemMatch"), key = "\$not", atlasSearch = atlasSearch, context = context)
        is Condition.SetAnyElements<*> -> into.sub(key)["\$elemMatch"] = (condition as Condition<Any?>).bson(serializer.listElement()!! as KSerializer<Any?>, context = context)
        is Condition.ListAllElements<*> -> (condition as Condition<Any?>).dump(serializer.listElement()!! as KSerializer<Any?>, into.sub(key).sub("\$not").sub("\$elemMatch"), key = "\$not", atlasSearch = atlasSearch, context = context)
        is Condition.ListAnyElements<*> -> into.sub(key)["\$elemMatch"] = (condition as Condition<Any?>).bson(serializer.listElement()!! as KSerializer<Any?>, context = context)
        is Condition.Exists<*> -> into[if (key == null) this.key else "$key.${this.key}"] = documentOf("\$exists" to true)
        is Condition.GreaterThan -> into.sub(key)["\$gt"] = value.let { bson.stringifyAny(serializer, it) }
        is Condition.LessThan -> into.sub(key)["\$lt"] = value.let { bson.stringifyAny(serializer, it) }
        is Condition.GreaterThanOrEqual -> into.sub(key)["\$gte"] = value.let { bson.stringifyAny(serializer, it) }
        is Condition.LessThanOrEqual -> into.sub(key)["\$lte"] = value.let { bson.stringifyAny(serializer, it) }
        is Condition.IfNotNull<*> -> (condition as Condition<Any?>).dump(serializer.nullElement()!! as KSerializer<Any?>, into, key, atlasSearch = atlasSearch, context = context)
        is Condition.Inside -> into.sub(key)["\$in"] = values.let { bson.stringifyAny(ListSerializer(serializer), it) }
        is Condition.NotInside -> into.sub(key)["\$nin"] = values.let { bson.stringifyAny(ListSerializer(serializer), it) }
        is Condition.IntBitsAnyClear -> into.sub(key)["\$bitsAllClear"] = mask
        is Condition.IntBitsAnySet -> into.sub(key)["\$bitsAllSet"] = mask
        is Condition.IntBitsClear -> into.sub(key)["\$bitsAnyClear"] = mask
        is Condition.IntBitsSet -> into.sub(key)["\$bitsAnySet"] = mask
        is Condition.Not -> {
            into["\$nor"] = listOf(condition.dump(serializer, key = key, atlasSearch = atlasSearch, context = context))
        }
//        is Condition.Not -> condition.dump(serializer, into.sub(key)["\$not"], key)
        is Condition.OnKey<*> -> (condition as Condition<Any?>).dump(serializer.mapValueElement() as KSerializer<Any?>, into, if (key == null) this.key else "$key.${this.key}", atlasSearch = atlasSearch, context = context)
        is Condition.GeoDistance -> into.sub(key)["\$geoWithin"] = documentOf(
            "\$centerSphere" to listOf(
                listOf(this.value.longitude, this.value.latitude),
                this.lessThanKilometers / 6378.1
            )
//            "\$maxDistance" to this.lessThanKilometers * 1000,
//            "\$minDistance" to this.greaterThanKilometers * 1000,
//            "\$geometry" to Serialization.Internal.bson.stringifyAny(GeoCoordinateGeoJsonSerializer, this.value),
        )
        is Condition.StringContains -> {
            into.sub(key).also {
                it["\$regex"] = Regex.escape(this.value)
                it["\$options"] = if(this.ignoreCase) "i" else ""
            }
        }
        is Condition.RawStringContains -> {
            into.sub(key).also {
                it["\$regex"] = Regex.escape(this.value)
                it["\$options"] = if(this.ignoreCase) "i" else ""
            }
        }
        is Condition.RegexMatches -> {
            into.sub(key).also {
                it["\$regex"] = this.pattern
                it["\$options"] = if(this.ignoreCase) "i" else ""
            }
        }
        is Condition.FullTextSearch -> {
            if(atlasSearch) {
                val terms = value.split(' ')
                val ser = DataClassPathSerializer(serializer)
                val paths = serializer.descriptor.annotations.filterIsInstance<TextIndex>().firstOrNull()?.fields?.map {
                    ser.fromString(it) as DataClassPath<T, String>
                }?.takeIf { it.isNotEmpty() } ?: return into
                val subs = terms.filter { !it.termShouldUseFuzzySearch() }
                    .map { term ->
                        Condition.Or(paths.map { it.mapCondition(Condition.StringContains(term, true)) })
                    }
                if(subs.isNotEmpty()) {
                    if (this.requireAllTermsPresent)
                        Condition.And(subs).dump(serializer, into, key, atlasSearch, context = context)
                    else
                        Condition.Or(subs).dump(serializer, into, key, atlasSearch, context = context)
                }
            } else into["\$text"] = documentOf(
                "\$search" to value,
                "\$caseSensitive" to false
            )
        }
        is Condition.SetSizesEquals<*> -> into.sub(key)["\$size"] = count
        is Condition.ListSizesEquals<*> -> into.sub(key)["\$size"] = count
        is Condition.OnField<*, *> -> (condition as Condition<Any?>).dump(this.key.serializer as KSerializer<Any?>, into, if (key == null) this.key.name else "$key.${this.key.name}", atlasSearch = atlasSearch, context = context)
    }
    return into
}

@Suppress("UNCHECKED_CAST")
private fun <T> Modification<T>.dump(serializer: KSerializer<T>, update: UpdateWithOptions = UpdateWithOptions(), key: String?, context: SettingContext): UpdateWithOptions {
    val into = update.document
    val bson = KBson(context.serializersModule, Configuration())

    when(this) {
        is Modification.Nothing -> TODO("Not supported")
        is Modification.Chain -> modifications.forEach { it.dump(serializer, update, key, context) }
        is Modification.Assign -> into["\$set", key] = value.let { bson.stringifyAny(serializer, it) }
        is Modification.CoerceAtLeast -> into["\$max", key] = value.let { bson.stringifyAny(serializer, it) }
        is Modification.CoerceAtMost -> into["\$min", key] = value.let { bson.stringifyAny(serializer, it) }
        is Modification.Increment -> into["\$inc", key] = by.let { bson.stringifyAny(serializer, it) }
        is Modification.Multiply -> into["\$mul", key] = by.let { bson.stringifyAny(serializer, it) }
        is Modification.AppendString -> TODO("Appending strings is not supported yet")
        is Modification.AppendRawString -> TODO("Appending raw strings is not supported yet")
        is Modification.IfNotNull<*> -> (modification as Modification<Any?>).dump(serializer.nullElement()!! as KSerializer<Any?>, update, key, context)
        is Modification.OnField<*, *> ->
            (modification as Modification<Any?>).dump(this.key.serializer as KSerializer<Any?>, update, if (key == null) this.key.name else "$key.${this.key.name}", context)
        is Modification.ListAppend<*> -> into.sub("\$push").sub(key)["\$each"] = items.let { bson.stringifyAny(serializer as KSerializer<List<Any?>>, it) }
        is Modification.ListRemove<*> -> into["\$pull", key] = (condition as Condition<Any?>).bson(serializer.listElement() as KSerializer<Any?>, context = context)
        is Modification.ListRemoveInstances<*> -> into["\$pullAll", key] = items.let { bson.stringifyAny(serializer as KSerializer<List<Any?>>, it) }
        is Modification.ListDropFirst<*> -> into["\$pop", key] = -1
        is Modification.ListDropLast<*> -> into["\$pop", key] = 1
        is Modification.ListPerElement<*> -> {
            val condIdentifier = genName()
            update.options = update.options.arrayFilters(
                (update.options.arrayFilters ?: listOf()) + (condition as Condition<Any?>).dump(serializer.listElement() as KSerializer<Any?>, key = condIdentifier, atlasSearch = false, context = context)
            )
            (modification as Modification<Any?>).dump(serializer.listElement() as KSerializer<Any?>, update, "$key.$[$condIdentifier]", context = context)
        }
        is Modification.SetAppend<*> -> into.sub("\$addToSet").sub(key)["\$each"] = items.let { bson.stringifyAny(serializer as KSerializer<Set<Any?>>, it) }
        is Modification.SetRemove<*> -> into["\$pull", key] = (condition as Condition<Any?>).bson(serializer.listElement() as KSerializer<Any?>, context = context)
        is Modification.SetRemoveInstances<*> -> into["\$pullAll", key] = items.let { bson.stringifyAny(serializer as KSerializer<Set<Any?>>, it) }
        is Modification.SetDropFirst<*> -> into["\$pop", key] = -1
        is Modification.SetDropLast<*> -> into["\$pop", key] = 1
        is Modification.SetPerElement<*> -> {
            val condIdentifier = genName()
            update.options = update.options.arrayFilters(
                (update.options.arrayFilters ?: listOf()) + (condition as Condition<Any?>).dump(serializer.listElement() as KSerializer<Any?>, key = condIdentifier, atlasSearch = false, context = context)
            )
            (modification as Modification<Any?>).dump(serializer.listElement() as KSerializer<Any?>, update, "$key.$[$condIdentifier]", context = context)
        }
        is Modification.Combine<*> -> map.forEach {
            into.sub("\$set")[if (key == null) it.key else "$key.${it.key}"] = it.value.let { bson.stringifyAny(serializer.mapValueElement() as KSerializer<Any?>, it) }
        }
        is Modification.ModifyByKey<*> -> map.forEach {
            (it.value as Modification<Any?>).dump(serializer.mapValueElement() as KSerializer<Any?>, update, if (key == null) it.key else "$key.${it.key}", context = context)
        }
        is Modification.RemoveKeys<*> -> this.fields.forEach {
            into.sub("\$unset")[if (key == null) it else "$key.${it}"] = ""
        }
    }
    return update
}


private fun Document.sub(key: String?): Document = if(key == null) this else getOrPut(key) { Document() } as Document
private operator fun Document.set(owner: String, key: String?, value: Any?) {
    if(key == null) this[owner] = value
    else this.sub(owner)[key] = value
}
private var lastNum = AtomicInteger()
private fun genName(): String {
    val r = 'a' + (lastNum.getAndIncrement() % 26)
    return r.toString()
}

public data class UpdateWithOptions(
    val document: Document = Document(),
    var options: UpdateOptions = UpdateOptions()
)

public fun <T> Condition<T>.bson(serializer: KSerializer<T>, atlasSearch: Boolean = false, context: SettingContext): Document = Document().also { dump(serializer, it, null, atlasSearch, context) }
public fun <T> Modification<T>.bson(serializer: KSerializer<T>, context: SettingContext): UpdateWithOptions = UpdateWithOptions().also { dump(serializer, it, null, context) }
public fun <T> UpdateWithOptions.upsert(model: T, serializer: KSerializer<T>, context: SettingContext): Boolean {
    val set: Document? = (document["\$set"] as? Document) ?: (document["\$set"] as? BsonDocument)?.toDocument()
    val inc = (document["\$inc"] as? Document) ?: (document["\$inc"] as? BsonDocument)?.toDocument()
    val restrict = document.entries.asSequence()
        .filter { it.key != "\$set" && it.key != "\$inc" }
        .map { it.value }
        .filterIsInstance<Document>()
        .flatMap { it.keys }
        .toSet()
    val bson = KBson(context.serializersModule, Configuration())
    document["\$setOnInsert"] = bson.stringify(serializer, model).toDocument().also {
        set?.keys?.forEach { k ->
            if(it[k] == set[k]) it.remove(k)
            else {
                return false
            }
        }
        inc?.keys?.forEach { k ->
            if((it[k] as Number).toDouble() == (inc[k] as BsonNumber).doubleValue()) it.remove(k)
            else {
                return false
            }
        }
        restrict.forEach { k ->
            if(it.containsKey(k)) return false
        }
    }
    options = options.upsert(true)
    return true
}

@OptIn(ExperimentalSerializationApi::class)
public fun SerialDescriptor.bsonType(module: SerializersModule): BsonType = when(kind) {
    SerialKind.ENUM -> BsonType.STRING
    SerialKind.CONTEXTUAL -> when(this.capturedKClass){
        ObjectId::class -> BsonType.OBJECT_ID
        BigDecimal::class -> BsonType.DECIMAL128
        ByteArray::class -> BsonType.BINARY
        Date::class -> BsonType.DATE_TIME
        Calendar::class -> BsonType.DATE_TIME
        GregorianCalendar::class -> BsonType.DATE_TIME
        Instant::class -> BsonType.DATE_TIME
        LocalDate::class -> BsonType.DATE_TIME
        LocalDateTime::class -> BsonType.DATE_TIME
        LocalTime::class -> BsonType.DATE_TIME
        BsonTimestamp::class -> BsonType.DATE_TIME
        Locale::class -> BsonType.STRING
        Binary::class -> BsonType.BINARY
        Pattern::class -> BsonType.DOCUMENT
        Regex::class -> BsonType.DOCUMENT
        UUID::class -> BsonType.BINARY
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