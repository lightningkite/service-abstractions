package com.lightningkite.serviceabstractions.cache.dynamodb

import com.lightningkite.serviceabstractions.SettingContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import software.amazon.awssdk.core.async.SdkPublisher
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.paginators.ScanPublisher
import java.util.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.modules.serializersModuleOf


private fun <T> SdkPublisher<Map<String, AttributeValue>>.parse(serializer: KSerializer<T>, module: SerializersModule): Flow<T> {
    return asFlow().map { serializer.fromDynamoMap(it, module) }
}

private fun json(module: SerializersModule): Json {
    return Json {
        ignoreUnknownKeys = true
        serializersModule = module
        encodeDefaults = true
    }
}

internal fun <T> KSerializer<T>.toDynamo(value: T, module: SerializersModule): AttributeValue {
    val jsonElement = json(module).encodeToJsonElement(this, value)
    return when (jsonElement) {
        is JsonObject -> AttributeValue.fromM(jsonElement.mapValues { it.value.toDynamoDb() })
        else -> jsonElement.toDynamoDb()
    }
}

// change test
internal fun <T> KSerializer<T>.fromDynamo(value: AttributeValue, module: SerializersModule): T {
    try {
        val element = value.toJson()
        return json(module).decodeFromJsonElement(this, element)
    } catch(e: Exception) {
        throw SerializationException("Could not parse $value as ${this.descriptor.serialName}", e)
    }
}

private fun <T> KSerializer<T>.toDynamoMap(value: T, module: SerializersModule): Map<String, AttributeValue> = toDynamo(value, module).m()
private fun <T> KSerializer<T>.fromDynamoMap(value: Map<String, AttributeValue>, module: SerializersModule): T = fromDynamo(AttributeValue.fromM(value), module)

private fun JsonElement.toDynamoDb(): AttributeValue {
    return when (this) {
        JsonNull -> AttributeValue.fromNul(true)
        is JsonPrimitive -> if (isString) AttributeValue.fromS(this.content)
        else if(content == "true" || content == "false") AttributeValue.fromBool(this.content.toBoolean())
        else AttributeValue.fromN(this.content)

        is JsonArray -> AttributeValue.fromL(this.map { it.toDynamoDb() })
        is JsonObject -> AttributeValue.fromM(this.mapValues { it.value.toDynamoDb() })
    }
}

private fun AttributeValue.toJson(): JsonElement {
    return when (this.type()) {
        null -> JsonNull
        AttributeValue.Type.S -> JsonPrimitive(this.s())
        AttributeValue.Type.N -> JsonPrimitive(this.n().let { it.toLongOrNull() ?: it.toDoubleOrNull() })
        AttributeValue.Type.B -> JsonPrimitive(Base64.getEncoder().encodeToString(this.b().asByteArray()))
        AttributeValue.Type.SS -> JsonArray(this.ss().map { JsonPrimitive(it) })
        AttributeValue.Type.NS -> JsonArray(this.ns().map { JsonPrimitive(it.toLongOrNull() ?: it.toDoubleOrNull()) })
        AttributeValue.Type.BS -> JsonArray(
            this.bs().map { JsonPrimitive(Base64.getEncoder().encodeToString(it.asByteArray())) })

        AttributeValue.Type.M -> JsonObject(this.m().mapValues { it.value.toJson() })
        AttributeValue.Type.L -> JsonArray(this.l().map { it.toJson() })
        AttributeValue.Type.BOOL -> JsonPrimitive(this.bool())
        AttributeValue.Type.NUL -> JsonNull
        AttributeValue.Type.UNKNOWN_TO_SDK_VERSION -> TODO()
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.dynamoType(module: SerializersModule): AttributeValue.Type = when {
    else -> when(this.kind) {
        PolymorphicKind.OPEN -> TODO()
        PolymorphicKind.SEALED -> TODO()
        PrimitiveKind.BOOLEAN -> AttributeValue.Type.BOOL
        PrimitiveKind.BYTE -> AttributeValue.Type.N
        PrimitiveKind.CHAR -> AttributeValue.Type.S
        PrimitiveKind.DOUBLE -> AttributeValue.Type.N
        PrimitiveKind.FLOAT -> AttributeValue.Type.N
        PrimitiveKind.INT -> AttributeValue.Type.N
        PrimitiveKind.LONG -> AttributeValue.Type.N
        PrimitiveKind.SHORT -> AttributeValue.Type.N
        PrimitiveKind.STRING -> AttributeValue.Type.S
        SerialKind.CONTEXTUAL -> module.getContextualDescriptor(this)!!.dynamoType(module)
        SerialKind.ENUM -> AttributeValue.Type.S
        StructureKind.CLASS -> AttributeValue.Type.M
        StructureKind.LIST -> AttributeValue.Type.L
        StructureKind.MAP -> AttributeValue.Type.M
        StructureKind.OBJECT -> AttributeValue.Type.S
    }
}