//package com.lightningkite.serviceabstractions.database
//
//import kotlinx.serialization.DeserializationStrategy
//import kotlinx.serialization.ExperimentalSerializationApi
//import kotlinx.serialization.Serializable
//import kotlinx.serialization.SerializationStrategy
//import kotlinx.serialization.descriptors.SerialDescriptor
//import kotlinx.serialization.encoding.AbstractDecoder
//import kotlinx.serialization.encoding.AbstractEncoder
//import kotlinx.serialization.encoding.CompositeDecoder
//import kotlinx.serialization.encoding.CompositeEncoder
//import kotlinx.serialization.modules.SerializersModule
//import kotlin.test.Test
//import kotlin.test.assertEquals
//
//@Serializable
//data class UnreasonablyComplex(
//    val string: String = "",
//    val int: Int = 0,
//    val list: List<String> = listOf(),
//    val map: Map<String, Int> = mapOf(),
//    val nested: UnreasonablyComplex? = null,
//    val listNested: List<UnreasonablyComplex> = listOf(),
//    val mapNested: Map<String, UnreasonablyComplex> = mapOf(),
//)
//
//class Supercoding {
//    @Test fun test() {
//        val en = MinEncoder(SerializersModule {})
//        val item = UnreasonablyComplex()
//        UnreasonablyComplex.serializer().serialize(en, item)
//        println(en.output)
//        val dec = MinDecoder(en.serializersModule, en.output)
//        val decoded = UnreasonablyComplex.serializer().deserialize(dec)
//        assertEquals(item, decoded)
//    }
//}
//
//
//@OptIn(ExperimentalSerializationApi::class)
//private class MinEncoder(
//    override val serializersModule: SerializersModule,
//) : AbstractEncoder() {
//    var output: Any? = null
//    val stack = ArrayList<ArrayList<Any?>>(4)
//    override fun encodeValue(value: Any) {
//        stack.lastOrNull()?.add(value) ?: run {
//            output = value
//        }
//    }
//    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
//        encodeValue(value as Any)
//    }
//    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
//        stack.add(ArrayList(descriptor.elementsCount))
//        return this
//    }
//    override fun endStructure(descriptor: SerialDescriptor) {
//        encodeValue(stack.removeLast())
//    }
//    override fun encodeNull() {
//        stack.lastOrNull()?.add(null) ?: run {
//            output = null
//        }
//    }
//}
//@OptIn(ExperimentalSerializationApi::class)
//private class MinDecoder(override val serializersModule: SerializersModule, var item: Any?) : AbstractDecoder() {
//    override fun decodeNotNullMark(): Boolean = item != null
//    override fun decodeValue(): Any = item!!
//    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T = decodeValue() as T
//    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = throw IllegalStateException("Use MinDecoderB")
//    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = MinDecoderB(serializersModule, item as List<*>)
//}
//@OptIn(ExperimentalSerializationApi::class)
//private class MinDecoderB(override val serializersModule: SerializersModule, var items: List<Any?>) : AbstractDecoder() {
//    private var elementIndex = 0
//    private var read = false
//    inline fun incr(): Int {
//        return if(read) elementIndex++
//        else elementIndex
//    }
//    override fun decodeNotNullMark(): Boolean = items[elementIndex] != null
//    override fun decodeNull(): Nothing? = null
//    override fun decodeValue(): Any {
//        println("Decoding index $elementIndex from $items")
//        return items[elementIndex]!!.also { read = true }
//    }
//    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T = decodeValue() as T
//    override fun decodeSequentially(): Boolean = true
//    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
//        if (elementIndex >= (items.size)) return CompositeDecoder.DECODE_DONE
//        return elementIndex
//    }
//    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = MinDecoderB(serializersModule, decodeValue() as List<*>)
//}