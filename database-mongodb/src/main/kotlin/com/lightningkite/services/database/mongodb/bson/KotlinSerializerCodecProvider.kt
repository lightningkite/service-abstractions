/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lightningkite.services.database.mongodb.bson

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import org.bson.codecs.Codec
import org.bson.codecs.configuration.CodecProvider
import org.bson.codecs.configuration.CodecRegistry
import java.lang.reflect.Type
import kotlin.jvm.kotlin

/**
 * A Kotlin Serialization based Codec Provider
 *
 * The underlying class must be annotated with the `@Serializable`.
 */
@OptIn(ExperimentalSerializationApi::class)
public class KotlinSerializerCodecProvider(
    private val serializersModule: SerializersModule = defaultSerializersModule,
    private val bsonConfiguration: BsonConfiguration = BsonConfiguration()
) : CodecProvider {

    override fun <T : Any> get(clazz: Class<T>, registry: CodecRegistry): Codec<T>? =
        KotlinSerializerCodec.create(clazz.kotlin, serializersModule, bsonConfiguration)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(clazz: Class<T>, typeArguments: List<Type>, registry: CodecRegistry): Codec<T> {
        return KotlinSerializerCodec.create(clazz.kotlin,
            serializersModule.serializer(clazz.kotlin, typeArguments.map {
                serializersModule.serializer(it)
            }, isNullable = false) as KSerializer<T>, serializersModule, bsonConfiguration)
    }
}

