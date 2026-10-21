@file:Suppress("DEPRECATION")

package com.lightningkite.services.database

import com.lightningkite.services.database.path.*
import kotlin.test.*

class DeprecatedApiTest {
    @Test
    fun oldPathSyntaxBuildsTheSameOptic() {
        val old: DataClassPath<LargeTestModel, String> =
            DataClassPathSelf(LargeTestModel.serializer())[LargeTestModel.embedded][ClassUsedForEmbedding.value1]
        assertEquals(LargeTestModel.embedded + ClassUsedForEmbedding.value1, old)
        assertEquals(LargeTestModel.int, DataClassPathAccess(DataClassPathSelf(LargeTestModel.serializer()), LargeTestModel.int))
    }

    @Test
    fun oldMembersStillWork() {
        val path: DataClassPathPartial<LargeTestModel> = LargeTestModel.embedded + ClassUsedForEmbedding.value1
        val model = LargeTestModel(embedded = ClassUsedForEmbedding(value1 = "a"))
        assertEquals("a", path.getAny(model))
        assertEquals("b", path.setAny(model, "b").embedded.value1)
        assertEquals(path.fields, path.properties)
        assertEquals(kotlinx.serialization.serializer<String>().descriptor, path.serializerAny.descriptor)
        assertEquals(LargeTestModel.serializer().fields!!.toList(), LargeTestModel.serializer().serializableProperties!!.toList())
        assertFalse(LargeTestModel.int.inline)
    }

    @Test
    fun oldSerializerParsesPaths() {
        val path = DataClassPathSerializer(LargeTestModel.serializer()).fromString("embedded.value1")
        assertEquals(LargeTestModel.embedded + ClassUsedForEmbedding.value1, path)
    }

    @Test
    fun oldModificationLookupsStillWork() {
        val modification = modification<LargeTestModel> { it.int assign 3 }
        assertEquals(Modification.Assign(3), modification.forDataClassPath(LargeTestModel.int))
        assertEquals(3, modification.valueSetForDataClassPath(LargeTestModel.int))
    }
}
