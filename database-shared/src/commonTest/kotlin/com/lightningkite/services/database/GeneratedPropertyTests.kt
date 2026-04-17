package com.lightningkite.services.database

import com.lightningkite.services.data.GenerateDataClassPaths
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.fail
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
data class DualTypeParamTestModel<T, Y>(
    val t: T,
    val y: Y,
    val title: String,
    val body: String,
    val url: String?,
    val otherValue: String,
)

@Serializable
@GenerateDataClassPaths
data class DualTypeParamTestModelPart2<T, Y>(
    val t: T,
    val y: Y,
    val title: String,
    val body: String,
    val url: String?,
    val otherValue: String,
)

@Serializable
@JvmInline
@GenerateDataClassPaths
value class LongWrapper(val long: Long)

@Serializable
@JvmInline
@GenerateDataClassPaths
value class IdWrapper(override val raw: Uuid) : TypedId<Uuid, IdWrapper>

@Serializable
@GenerateDataClassPaths
data class ClassWithWrappedValue(
    val long: LongWrapper = LongWrapper(0),
    override val _id: IdWrapper = IdWrapper(Uuid.random()),
) : HasId<IdWrapper>

@Serializable
data class NestedType(
    val nestedType: NestedType? = null,
    val name: String = "Hello World"
) {
    constructor(depth: Int) : this(
        Unit.run {
            fun nest(depth: Int): NestedType = NestedType(
                nestedType = if (depth > 0) nest(depth - 1) else null,
                name = if (depth == 0) "Root" else "Layer $depth"
            )
            nest(depth)
        }
    )

    fun edit(depth: Int, edit: (NestedType) -> NestedType): NestedType {
        fun NestedType.modify(currentDepth: Int): NestedType {
            return if (currentDepth == depth) edit(this)
            else copy(nestedType = nestedType?.modify(currentDepth + 1))
        }
        return this.modify(0)
    }
}

class GeneratedPropertyTests {

    @Test
    fun conditionEqualityTest() {

        // Simple Equal
        var condition1: Condition<*> = condition<DualTypeParamTestModel<Uuid, Int>> { it.otherValue.eq("") }
        var condition2: Condition<*> = condition<DualTypeParamTestModel<Uuid, Int>> { it.otherValue.eq("") }
        assertEquals(condition1, condition2)

        // Simple Not Equal
        condition1 = condition<DualTypeParamTestModel<Uuid, Long>> { it.otherValue.eq("") }
        condition2 = condition<DualTypeParamTestModel<Uuid, Uuid>> { it.otherValue.eq("") }
        assertNotEquals(condition1 as Any, condition2 as Any)

        // Complicated Equal
        condition1 =
            condition<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<DualTypeParamTestModel<String, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<Int, Long>>, Long>>>>, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, Long>>, Long>>>>>> {
                it.otherValue.eq("")
            }
        condition2 =
            condition<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<DualTypeParamTestModel<String, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<Int, Long>>, Long>>>>, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, Long>>, Long>>>>>> {
                it.otherValue.eq("")
            }
        assertEquals(condition1, condition2)

        // Complicated Not Equal
        condition1 =
            condition<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<DualTypeParamTestModel<String, DualTypeParamTestModel<Long, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<Int, Long>>, Long>>>>, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, Long>>, Long>>>>>> {
                it.otherValue.eq("")
            }
        condition2 =
            condition<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<DualTypeParamTestModel<String, DualTypeParamTestModel<Int, DualTypeParamTestModel<Short, DualTypeParamTestModel<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<Int, Long>>, Long>>>>, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, Long>>, Long>>>>>> {
                it.otherValue.eq("")
            }
        assertNotEquals(condition1 as Any, condition2 as Any)

        // Extreme Differences
        condition1 = condition<DualTypeParamTestModel<Uuid, Long>> { it.otherValue.eq("") }
        condition2 =
            condition<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<DualTypeParamTestModel<String, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Uuid, DualTypeParamTestModel<Int, Long>>, Long>>>>, DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, DualTypeParamTestModel<DualTypeParamTestModel<Int, DualTypeParamTestModel<Int, Long>>, Long>>>>>> {
                it.otherValue.eq("")
            }
        assertNotEquals(condition1 as Any, condition2 as Any)

        // Different Models
        condition1 = condition<DualTypeParamTestModelPart2<Uuid, Long>> { it.otherValue.eq("") }
        condition2 = condition<DualTypeParamTestModel<Uuid, Long>> { it.otherValue.eq("") }
        assertNotEquals(condition1 as Any, condition2 as Any)
    }


    fun <T> encodeDecode(value: T, serializer: KSerializer<T>) {
        val enc = Json.encodeToString(serializer, value)
        val dec = Json.decodeFromString(serializer, enc)
        assertEquals(value, dec)
    }

    @Test
    fun inlineTest() {
        val ser = LongWrapper.serializer()
        println("Element count: ${ser.descriptor.elementsCount}")

        val properties = ser.serializableProperties

        if (properties == null) fail("No generated properties")
        else {
            assertEquals(1, properties.size)
            println("Properties (${properties.size}): ${properties.joinToString { if (it.inline) "${it.name} (inline)" else it.name }}")
        }

        val prop = LongWrapper.path.long

        assertEquals(1L, prop.get(LongWrapper(1L)))
        assertEquals(LongWrapper(5L), prop.set(LongWrapper(0L), 5L))

        val cond = condition<LongWrapper> { it.long eq 5L }
        val mod = modification<LongWrapper> { it.long assign 5L }

        assertEquals(true, cond(LongWrapper(5L)))
        assertEquals(false, cond(LongWrapper(0L)))
        assertEquals(LongWrapper(5L), mod(LongWrapper(0L)))

        println("Mod: $mod")

        encodeDecode(cond, Condition.serializer(LongWrapper.serializer()))
        encodeDecode(mod, Modification.serializer(LongWrapper.serializer()))

        val prop2 = ClassWithWrappedValue.path.long.long

        assertEquals(1L, prop2.get(ClassWithWrappedValue(LongWrapper(1L))))

        val id = IdWrapper(Uuid.random())

        assertEquals(ClassWithWrappedValue(LongWrapper(5L), id), prop2.set(ClassWithWrappedValue(LongWrapper(0L), id), 5L))

        val cond2 = condition<ClassWithWrappedValue> { it.long.long eq 5L }
        val mod2 = modification<ClassWithWrappedValue> { it.long.long assign 5L }

        assertEquals(true, cond2(ClassWithWrappedValue(LongWrapper(5L))))
        assertEquals(false, cond2(ClassWithWrappedValue(LongWrapper(0L))))
        assertEquals(ClassWithWrappedValue(LongWrapper(5L), id), mod2(ClassWithWrappedValue(LongWrapper(0L), id)))

        println("Mod: $mod")

        encodeDecode(cond2, Condition.serializer(ClassWithWrappedValue.serializer()))
        encodeDecode(mod2, Modification.serializer(ClassWithWrappedValue.serializer()))
    }

    fun <T> modification(serializer: KSerializer<T>, old: T, new: T): Modification<T>? = run {  // ripped from ls-kiteui (maybe we should just move into service abstractions)
        if (old == new) return@run null
        if (old == null || new == null) return@run Modification.Assign(new)
        return@run (serializer.nullElement() ?: serializer).serializableProperties.also { println("Properties: ${it?.joinToString { it.name }}") }?.let {
            Modification.Chain<T>(it.mapNotNull { prop ->
                @Suppress("UNCHECKED_CAST")
                prop as SerializableProperty<T, Any?>
                val oldValue = prop.get(old as T)
                val newValue = prop.get(new as T)   // issue comes from SerializableProperty.get going to MinEncoder
                println("New value for ${prop.name} is $newValue of type ${newValue?.let {it::class}}, type is ${prop.serializer.descriptor.serialName}")
                val inner = modification(prop.serializer, oldValue, newValue)?.let { mod ->
                    if(prop.serializer.descriptor.isNullable && mod !is Modification.Assign<*>)
                        Modification.IfNotNull(mod)
                    else mod
                } ?: return@mapNotNull null
                Modification.OnField(prop, inner).also { println("modifying ${prop.name} -> $it") }
            })
        } ?: Modification.Assign<T>(new)
    }

    @Test
    fun testNestedEncoding() {
        val t1 = NestedType(5)
        val t2 = t1.edit(3) { it.copy(name = "Changed Name") }
        val mod = modification(NestedType.serializer(), t1, t2)
        println("Mod: $mod")
    }

    @Test
    fun `test super weird edge case that took me four hours of debugging to find May god have mercy on your soul if this fails`() {

        val t1 = ClassWithWrappedValue()
        val t2 = t1.copy(_id = IdWrapper(Uuid.random()))

        val mod = modification(ClassWithWrappedValue.serializer(), t1, t2)!!

        encodeDecode(mod, Modification.serializer(ClassWithWrappedValue.serializer()))
    }
}