// by Claude - Tests for recursive VirtualStruct types
package com.lightningkite.services.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

class VirtualStructRecursionTest {

    private val registry = SerializationRegistry(EmptySerializersModule())
    private val json = Json { prettyPrint = true }

    @Test
    fun `direct self-reference works`() {
        // data class Node(val _id: Uuid, val child: Node?)
        val nodeStruct = VirtualStruct(
            serialName = "Node",
            annotations = listOf(),
            fields = listOf(
                VirtualField(
                    index = 0,
                    name = "_id",
                    type = VirtualTypeReference("kotlin.uuid.Uuid", listOf(), isNullable = false),
                    optional = false,
                    annotations = listOf()
                ),
                VirtualField(
                    index = 1,
                    name = "child",
                    type = VirtualTypeReference("Node", listOf(), isNullable = true),
                    optional = true,
                    annotations = listOf()
                    // by Claude - Removed defaultJson to simplify test; null will be the default for nullable optional fields
                )
            ),
            parameters = listOf()
        )
        registry.register(nodeStruct)

        // by Claude - Get the Concrete from the registry, don't create a new one
        val concrete = registry["Node", arrayOf()] as VirtualStruct.Concrete
        assertNotNull(concrete.descriptor)
        assertEquals(2, concrete.descriptor.elementsCount)
        assertEquals("_id", concrete.descriptor.getElementName(0))
        assertEquals("child", concrete.descriptor.getElementName(1))

        // Test serialization round-trip using direct value construction
        val id1 = Uuid.random()
        val id2 = Uuid.random()
        // Create instances directly using VirtualInstance constructor to avoid default generation
        val child = VirtualInstance(concrete, listOf(id2, null))
        val parent = VirtualInstance(concrete, listOf(id1, child))

        val encoded = json.encodeToString(concrete, parent)
        println("Encoded: $encoded")
        val decoded = json.decodeFromString(concrete, encoded)
        assertEquals(id1, decoded.values[0])
        assertNotNull(decoded.values[1])
        assertEquals(id2, (decoded.values[1] as VirtualInstance).values[0])
    }

    @Test
    fun `mutual recursion works`() {
        // data class A(val _id: Uuid, val b: B?)
        // data class B(val _id: Uuid, val a: A?)
        val structA = VirtualStruct(
            serialName = "MutualA",
            annotations = listOf(),
            fields = listOf(
                VirtualField(
                    index = 0,
                    name = "_id",
                    type = VirtualTypeReference("kotlin.uuid.Uuid", listOf(), isNullable = false),
                    optional = false,
                    annotations = listOf()
                ),
                VirtualField(
                    index = 1,
                    name = "b",
                    type = VirtualTypeReference("MutualB", listOf(), isNullable = true),
                    optional = true,
                    annotations = listOf()
                )
            ),
            parameters = listOf()
        )

        val structB = VirtualStruct(
            serialName = "MutualB",
            annotations = listOf(),
            fields = listOf(
                VirtualField(
                    index = 0,
                    name = "_id",
                    type = VirtualTypeReference("kotlin.uuid.Uuid", listOf(), isNullable = false),
                    optional = false,
                    annotations = listOf()
                ),
                VirtualField(
                    index = 1,
                    name = "a",
                    type = VirtualTypeReference("MutualA", listOf(), isNullable = true),
                    optional = true,
                    annotations = listOf()
                )
            ),
            parameters = listOf()
        )

        // Register both before instantiating
        registry.register(structA)
        registry.register(structB)

        // by Claude - Get the Concretes from the registry
        val concreteA = registry["MutualA", arrayOf()] as VirtualStruct.Concrete
        val concreteB = registry["MutualB", arrayOf()] as VirtualStruct.Concrete

        assertNotNull(concreteA.descriptor)
        assertNotNull(concreteB.descriptor)

        // Test serialization round-trip using direct construction
        val idA = Uuid.random()
        val idB = Uuid.random()
        val instanceB = VirtualInstance(concreteB, listOf(idB, null))
        val instanceA = VirtualInstance(concreteA, listOf(idA, instanceB))

        val encoded = json.encodeToString(concreteA, instanceA)
        println("Encoded A with B: $encoded")
        val decoded = json.decodeFromString(concreteA, encoded)
        assertEquals(idA, decoded.values[0])
        assertNotNull(decoded.values[1])
        assertEquals(idB, (decoded.values[1] as VirtualInstance).values[0])
    }

    @Test
    fun `self-reference in list works`() {
        // data class TreeNode(val _id: Uuid, val children: List<TreeNode>)
        val treeStruct = VirtualStruct(
            serialName = "TreeNode",
            annotations = listOf(),
            fields = listOf(
                VirtualField(
                    index = 0,
                    name = "_id",
                    type = VirtualTypeReference("kotlin.uuid.Uuid", listOf(), isNullable = false),
                    optional = false,
                    annotations = listOf()
                ),
                VirtualField(
                    index = 1,
                    name = "children",
                    type = VirtualTypeReference(
                        "kotlin.collections.ArrayList",
                        listOf(VirtualTypeReference("TreeNode", listOf(), isNullable = false)),
                        isNullable = false
                    ),
                    optional = true,
                    annotations = listOf()
                )
            ),
            parameters = listOf()
        )
        registry.register(treeStruct)

        // by Claude - Get from registry
        val concrete = registry["TreeNode", arrayOf()] as VirtualStruct.Concrete
        assertNotNull(concrete.descriptor)

        // Test with nested children using direct construction
        val id1 = Uuid.random()
        val id2 = Uuid.random()
        val id3 = Uuid.random()
        val leaf1 = VirtualInstance(concrete, listOf(id2, emptyList<VirtualInstance>()))
        val leaf2 = VirtualInstance(concrete, listOf(id3, emptyList<VirtualInstance>()))
        val parent = VirtualInstance(concrete, listOf(id1, listOf(leaf1, leaf2)))

        val encoded = json.encodeToString(concrete, parent)
        println("Encoded tree: $encoded")
        val decoded = json.decodeFromString(concrete, encoded)
        assertEquals(id1, decoded.values[0])
        @Suppress("UNCHECKED_CAST")
        val children = decoded.values[1] as List<VirtualInstance>
        assertEquals(2, children.size)
        assertEquals(id2, children[0].values[0])
        assertEquals(id3, children[1].values[0])
    }

    @Test
    fun `three-way mutual recursion works`() {
        // data class X(val _id: Uuid, val y: Y?)
        // data class Y(val _id: Uuid, val z: Z?)
        // data class Z(val _id: Uuid, val x: X?)
        val structX = VirtualStruct(
            serialName = "ThreeWayX",
            annotations = listOf(),
            fields = listOf(
                VirtualField(0, "_id", VirtualTypeReference("kotlin.uuid.Uuid", listOf(), false), false, listOf()),
                VirtualField(1, "y", VirtualTypeReference("ThreeWayY", listOf(), true), true, listOf())
            ),
            parameters = listOf()
        )
        val structY = VirtualStruct(
            serialName = "ThreeWayY",
            annotations = listOf(),
            fields = listOf(
                VirtualField(0, "_id", VirtualTypeReference("kotlin.uuid.Uuid", listOf(), false), false, listOf()),
                VirtualField(1, "z", VirtualTypeReference("ThreeWayZ", listOf(), true), true, listOf())
            ),
            parameters = listOf()
        )
        val structZ = VirtualStruct(
            serialName = "ThreeWayZ",
            annotations = listOf(),
            fields = listOf(
                VirtualField(0, "_id", VirtualTypeReference("kotlin.uuid.Uuid", listOf(), false), false, listOf()),
                VirtualField(1, "x", VirtualTypeReference("ThreeWayX", listOf(), true), true, listOf())
            ),
            parameters = listOf()
        )

        registry.register(structX)
        registry.register(structY)
        registry.register(structZ)

        // by Claude - Get from registry
        val concreteX = registry["ThreeWayX", arrayOf()] as VirtualStruct.Concrete
        val concreteY = registry["ThreeWayY", arrayOf()] as VirtualStruct.Concrete
        val concreteZ = registry["ThreeWayZ", arrayOf()] as VirtualStruct.Concrete

        assertNotNull(concreteX.descriptor)
        assertNotNull(concreteY.descriptor)
        assertNotNull(concreteZ.descriptor)

        // Test X -> Y -> Z -> X cycle using direct construction
        val idX = Uuid.random()
        val idY = Uuid.random()
        val idZ = Uuid.random()
        val instanceX2 = VirtualInstance(concreteX, listOf(idX, null))  // leaf X with no Y
        val instanceZ = VirtualInstance(concreteZ, listOf(idZ, instanceX2))
        val instanceY = VirtualInstance(concreteY, listOf(idY, instanceZ))
        val instanceX = VirtualInstance(concreteX, listOf(idX, instanceY))

        val encoded = json.encodeToString(concreteX, instanceX)
        println("Encoded three-way: $encoded")
        val decoded = json.decodeFromString(concreteX, encoded)
        assertEquals(idX, decoded.values[0])
    }
}
