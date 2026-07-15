package com.lightningkite.services.files

import com.lightningkite.services.data.Description
import com.lightningkite.services.data.ExperimentalLightningServer
import com.lightningkite.services.database.InliningSerialDescriptor
import com.lightningkite.services.database.PrimitiveDescriptorWithAnnotations
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.InlinePrimitiveDescriptor

public object DirectServerFileSerializer : KSerializer<ServerFile> {
    @OptIn(ExperimentalLightningServer::class)
    override val descriptor: SerialDescriptor = PrimitiveDescriptorWithAnnotations("com.lightningkite.services.files.ServerFile", PrimitiveKind.STRING, listOf(
        Description("A URL referencing a file that the server owns.")
    ))

    override fun serialize(encoder: Encoder, value: ServerFile) {
        encoder.encodeString(value.location)
    }

    override fun deserialize(decoder: Decoder): ServerFile {
        return ServerFile(decoder.decodeString())
    }
}

@OptIn(ExperimentalSerializationApi::class)
public object DeferToContextualServerFileSerializer : KSerializer<ServerFile> {
    private val c = ContextualSerializer<ServerFile>(ServerFile::class, DirectServerFileSerializer, arrayOf())

    @OptIn(ExperimentalLightningServer::class)
    override val descriptor: SerialDescriptor = InliningSerialDescriptor("com.lightningkite.services.files.ServerFile", c.descriptor)

    override fun deserialize(decoder: Decoder): ServerFile = decoder.decodeInline(descriptor).decodeSerializableValue(c)
    override fun serialize(
        encoder: Encoder,
        value: ServerFile,
    ): Unit = encoder.encodeInline(descriptor).encodeSerializableValue(c, value)
}
