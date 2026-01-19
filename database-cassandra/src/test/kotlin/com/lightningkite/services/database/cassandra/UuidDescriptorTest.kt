package com.lightningkite.services.database.cassandra

import com.lightningkite.services.database.test.LargeTestModel
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.uuid.Uuid
import kotlinx.serialization.serializer
import org.junit.Test

class UuidDescriptorTest {

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun checkUuidDescriptor() {
        val uuidDescriptor = serializer<Uuid>().descriptor
        println("=== Uuid Descriptor ===")
        println("serialName: ${uuidDescriptor.serialName}")
        println("kind: ${uuidDescriptor.kind}")
        println("isInline: ${uuidDescriptor.isInline}")
        println("isNullable: ${uuidDescriptor.isNullable}")
        println("elementsCount: ${uuidDescriptor.elementsCount}")
        if (uuidDescriptor.elementsCount > 0) {
            val inner = uuidDescriptor.getElementDescriptor(0)
            println("  inner[0].serialName: ${inner.serialName}")
            println("  inner[0].kind: ${inner.kind}")
        }

        println("\n=== kotlin.time.Instant Descriptor ===")
        val ktInstantDescriptor = serializer<kotlin.time.Instant>().descriptor
        println("serialName: ${ktInstantDescriptor.serialName}")
        println("kind: ${ktInstantDescriptor.kind}")
        println("isInline: ${ktInstantDescriptor.isInline}")

        println("\n=== kotlinx.datetime.Instant Descriptor ===")
        val kxInstantDescriptor = serializer<kotlinx.datetime.Instant>().descriptor
        println("serialName: ${kxInstantDescriptor.serialName}")
        println("kind: ${kxInstantDescriptor.kind}")
        println("isInline: ${kxInstantDescriptor.isInline}")

        // Check LargeTestModel field descriptors
        println("\n=== LargeTestModel Field Descriptors ===")
        val modelDescriptor = LargeTestModel.serializer().descriptor
        for (i in 0 until modelDescriptor.elementsCount) {
            val fieldName = modelDescriptor.getElementName(i)
            if (fieldName.contains("uuid", ignoreCase = true) || fieldName.contains("instant", ignoreCase = true)) {
                val fieldDescriptor = modelDescriptor.getElementDescriptor(i)
                println("$fieldName: serialName=${fieldDescriptor.serialName}, kind=${fieldDescriptor.kind}, isNullable=${fieldDescriptor.isNullable}")
            }
        }
    }
}
