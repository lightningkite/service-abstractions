package com.lightningkite.services.database

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.serializers.KotlinBytesFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
data class TestUser(
    val _id: Uuid = Uuid.random(),
    val email: String = "user@example.com",
    val username: String = "user",
    val role: Role = Role.User,
    val credits: Int = 0,
    val age: Int = 25,
    val isActive: Boolean = true,
    val score: Double = 0.0,
)

@Serializable
enum class Role {
    User,
    Moderator,
    Admin
}

@Serializable
@GenerateDataClassPaths
data class TestAddress(
    val city: String = "Springfield",
    val zip: String = "00000",
)

@Serializable
@GenerateDataClassPaths
data class TestProfile(
    val _id: Uuid = Uuid.random(),
    val name: String = "name",
    val address: TestAddress = TestAddress(),
)

/** Stands in for a build that predates [ModelPermissions.updateRestriction], for migration tests. */
@Serializable
data class ModelPermissionsWithoutRestriction<Model>(
    val create: Condition<Model> = Condition.Never,
    val read: Condition<Model> = Condition.Never,
    val update: Condition<Model> = Condition.Never,
    val delete: Condition<Model> = Condition.Never,
)

class UpdateRestrictionTest {

    // ==================== Basics ====================

    @Test
    fun `an empty restriction permits everything`() {
        val restriction = updateRestriction<TestUser> { }

        assertEquals(Condition.Always, restriction(modification { it.email assign "new@example.com" }))
        assertEquals(Condition.Always, restriction(modification { it.role assign Role.Admin }))
        assertEquals(Condition.Always, restriction(modification { it.credits assign 100 }))
        assertEquals(Condition.Always, restriction(Modification.Assign(TestUser())))
    }

    @Test
    fun `cannotBeModified blocks a field completely`() {
        val restriction = updateRestriction<TestUser> { user ->
            user._id.cannotBeModified()
            user.email.cannotBeModified()
        }

        assertEquals(Condition.Never, restriction(modification { it._id assign Uuid.random() }))
        assertEquals(Condition.Never, restriction(modification { it.email assign "new@example.com" }))
        assertEquals(Condition.Always, restriction(modification { it.username assign "newuser" }))
    }

    @Test
    fun `requires restricts a field to records matching a condition`() {
        val restriction = updateRestriction<TestUser> { user ->
            user.role requires (user.role eq Role.Admin)
        }

        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restriction(modification { it.role assign Role.Moderator })
        )
        assertEquals(Condition.Always, restriction(modification { it.username assign "newuser" }))
    }

    @Test
    fun `separate requires clauses on different fields combine`() {
        val restriction = updateRestriction<TestUser> { user ->
            user.role requires (user.role eq Role.Admin)
            user.credits requires (user.role eq Role.Admin)
        }
        val adminOnly = condition<TestUser> { it.role eq Role.Admin }

        assertEquals(adminOnly, restriction(modification { it.role assign Role.Moderator }))
        assertEquals(adminOnly, restriction(modification { it.credits assign 1000 }))
        assertEquals(adminOnly, restriction(modification {
            it.role assign Role.Moderator
            it.credits assign 1000
        }))
    }

    @Test
    fun `repeated clauses on the same field are ANDed`() {
        val restriction = updateRestriction<TestUser> { user ->
            user.credits requires (user.isActive eq true)
            user.credits requires (user.role eq Role.Admin)
        }

        assertEquals(
            condition<TestUser> { (it.isActive eq true) and (it.role eq Role.Admin) },
            restriction(modification { it.credits assign 100 })
        )
        assertEquals(Condition.Always, restriction(modification { it.username assign "newuser" }))
    }

    @Test
    fun `mustBe restricts what a field can be changed to`() {
        val restriction = updateRestriction<TestUser> { user ->
            user.age.mustBe { it gte 0 }
            user.score.mustBe { (it gte 0.0) and (it lte 100.0) }
        }

        assertEquals(Condition.Always, restriction(modification { it.age assign 30 }))
        assertEquals(Condition.Never, restriction(modification { it.age assign -1 }))
        assertEquals(Condition.Always, restriction(modification { it.score assign 75.0 }))
        assertEquals(Condition.Never, restriction(modification { it.score assign 200.0 }))
    }

    @Test
    fun `requires and mustBe on one field combine into a single rule`() {
        val restriction = updateRestriction<TestUser> { user ->
            user.credits requires (user.role eq Role.Admin)
            user.credits.mustBe { it gt 0 }
        }

        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restriction(modification { it.credits assign 100 })
        )
        // The value limit is not satisfiable, so no record qualifies regardless of role.
        assertEquals(Condition.Never, restriction(modification { it.credits assign -1 }))
    }

    @Test
    fun `several fields with different kinds of restriction`() {
        val restriction = updateRestriction<TestUser> { user ->
            user._id.cannotBeModified()
            user.role requires (user.role eq Role.Admin)
            user.credits requires (user.role eq Role.Admin)
            user.credits.mustBe { it gte 0 }
            user.isActive.mustBe { it eq true }
        }
        val adminOnly = condition<TestUser> { it.role eq Role.Admin }

        assertEquals(Condition.Never, restriction(modification { it._id assign Uuid.random() }))
        assertEquals(adminOnly, restriction(modification { it.role assign Role.Moderator }))
        assertEquals(adminOnly, restriction(modification { it.credits assign 100 }))
        assertEquals(Condition.Never, restriction(modification { it.isActive assign false }))
        assertEquals(Condition.Always, restriction(modification { it.username assign "newname" }))
    }

    @Test
    fun `a compound condition on the record survives into the result`() {
        val restriction = updateRestriction<TestUser> { user ->
            user.email requires ((user.role eq Role.Admin) and (user.isActive eq true))
        }

        assertEquals(
            condition<TestUser> { (it.role eq Role.Admin) and (it.isActive eq true) },
            restriction(modification { it.email assign "new@example.com" })
        )
    }

    // ==================== Whitelisting ====================

    @Test
    fun `onlyModifiable blocks everything it does not list`() {
        val restriction = updateRestriction<TestUser> { user ->
            onlyModifiable(user.username, user.age)
        }

        assertEquals(Condition.Always, restriction(modification { it.username assign "newuser" }))
        assertEquals(Condition.Always, restriction(modification { it.age assign 30 }))
        assertEquals(Condition.Never, restriction(modification { it.email assign "new@example.com" }))
        assertEquals(Condition.Never, restriction(modification { it.role assign Role.Admin }))
        assertEquals(Condition.Never, restriction(modification { it.credits assign 1000 }))
    }

    @Test
    fun `onlyModifiable listing nothing blocks everything`() {
        val restriction = updateRestriction<TestUser> { onlyModifiable() }

        assertEquals(Condition.Never, restriction(modification { it.username assign "newuser" }))
        assertEquals(Condition.Never, restriction(modification { it.email assign "new@example.com" }))
    }

    @Test
    fun `a whitelisted field can still carry its own restrictions`() {
        val restriction = updateRestriction<TestUser> { user ->
            onlyModifiable(user.username, user.age, user.credits)
            user.age.mustBe { (it gte 0) and (it lt 150) }
            user.credits requires (user.role eq Role.Admin)
        }

        assertEquals(Condition.Always, restriction(modification { it.username assign "newuser" }))
        assertEquals(Condition.Always, restriction(modification { it.age assign 30 }))
        assertEquals(Condition.Never, restriction(modification { it.age assign 200 }))
        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restriction(modification { it.credits assign 5 })
        )
        assertEquals(Condition.Never, restriction(modification { it.role assign Role.Admin }))
    }

    @Test
    fun `a whole-object write is refused by a whitelist`() {
        val restriction = updateRestriction<TestUser> { user -> onlyModifiable(user.username) }
        assertEquals(Condition.Never, restriction(Modification.Assign(TestUser())))
    }

    @Test
    fun `overwriting a parent does not count as touching only an allowed child`() {
        val restriction = updateRestriction<TestProfile> { profile -> onlyModifiable(profile.address.zip) }

        assertEquals(Condition.Always, restriction(modification { it.address.zip assign "99999" }))
        // Assigning the whole address also rewrites `city`, which is not allowed.
        assertEquals(Condition.Never, restriction(modification { it.address assign TestAddress() }))
    }

    // ==================== Chained modifications ====================

    @Test
    fun `a chain is allowed only if every part of it is`() {
        val restriction = updateRestriction<TestUser> { user ->
            user.role requires (user.role eq Role.Admin)
            user.email.cannotBeModified()
        }

        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restriction(modification {
                it.role assign Role.Moderator
                it.username assign "newuser"
            })
        )
        // The blocked field poisons the whole chain.
        assertEquals(Condition.Never, restriction(modification {
            it.role assign Role.Moderator
            it.email assign "new@example.com"
        }))
    }

    @Test
    fun `an empty chain is a no-op and is always allowed`() {
        val restriction = updateRestriction<TestUser> { user ->
            user.role.cannotBeModified()
            onlyModifiable(user.username)
        }
        assertEquals(Condition.Always, restriction(Modification.Chain(emptyList())))
    }

    // ==================== Nested paths ====================

    @Test
    fun `a restriction on a child field applies when the parent is overwritten wholesale`() {
        val restriction = updateRestriction<TestProfile> { profile ->
            profile.address.zip requires (profile.name eq "admin")
        }
        val adminOnly = condition<TestProfile> { it.name eq "admin" }

        assertEquals(adminOnly, restriction(modification { it.address.zip assign "99999" }))
        assertEquals(adminOnly, restriction(modification { it.address assign TestAddress(zip = "11111") }))
        // A sibling field is untouched by the rule.
        assertEquals(Condition.Always, restriction(modification { it.address.city assign "Shelbyville" }))
    }

    @Test
    fun `a restriction on a parent field governs modifications to its children`() {
        val restriction = updateRestriction<TestProfile> { profile ->
            profile.address requires (profile.name eq "admin")
        }
        val adminOnly = condition<TestProfile> { it.name eq "admin" }

        assertEquals(adminOnly, restriction(modification { it.address.zip assign "99999" }))
        assertEquals(adminOnly, restriction(modification { it.address assign TestAddress() }))
        assertEquals(Condition.Always, restriction(modification { it.name assign "someone" }))
    }

    // ==================== anyOf ====================

    @Test
    fun `anyOf declares alternatives that are OR'd together`() {
        val restriction = updateRestriction<TestUser> { user ->
            anyOf(
                // Admins may change the role to anything
                { user.role requires (user.role eq Role.Admin) },
                // Anyone may set their own role down to plain User
                { user.role.mustBe { it eq Role.User } },
            )
        }

        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restriction(modification { it.role assign Role.Admin })
        )
        assertEquals(Condition.Always, restriction(modification { it.role assign Role.User }))
    }

    @Test
    fun `an anyOf alternative may span several fields`() {
        // Admins may do anything to credits; moderators may hand out small amounts, but only to active accounts.
        val restriction = updateRestriction<TestUser> { user ->
            anyOf(
                { user.credits requires (user.role eq Role.Admin) },
                {
                    user.credits requires (user.role eq Role.Moderator)
                    user.credits.mustBe { it lt 100 }
                    user.isActive.cannotBeModified()
                },
            )
        }

        // A big grant needs admin -- the moderator alternative cannot prove the value limit.
        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restriction(modification { it.credits assign 500 })
        )
        // A small grant works for either; simplify folds the OR'd equalities into a membership check.
        assertEquals(
            condition<TestUser> { it.role inside setOf(Role.Admin, Role.Moderator) },
            restriction(modification { it.credits assign 50 })
        )
        // Touching isActive as well knocks out the moderator alternative entirely.
        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restriction(modification {
                it.credits assign 50
                it.isActive assign false
            })
        )
    }

    @Test
    fun `anyOf nests`() {
        val restriction = updateRestriction<TestUser> { user ->
            anyOf(
                { user.role requires (user.role eq Role.Admin) },
                {
                    anyOf(
                        { user.role.mustBe { it eq Role.User } },
                        { user.role requires (user.isActive eq true) },
                    )
                },
            )
        }

        assertEquals(Condition.Always, restriction(modification { it.role assign Role.User }))
        assertEquals(
            condition<TestUser> { (it.role eq Role.Admin) or (it.isActive eq true) },
            restriction(modification { it.role assign Role.Admin })
        )
    }

    @Test
    fun `a clause outside anyOf applies to every alternative`() {
        val restriction = updateRestriction<TestUser> { user ->
            // Only active accounts may touch credits at all...
            user.credits requires (user.isActive eq true)
            // ...and then only admins, or moderators handing out small amounts.
            anyOf(
                { user.credits requires (user.role eq Role.Admin) },
                {
                    user.credits requires (user.role eq Role.Moderator)
                    user.credits.mustBe { it lt 100 }
                },
            )
        }

        assertEquals(
            condition<TestUser> { (it.isActive eq true) and (it.role eq Role.Admin) },
            restriction(modification { it.credits assign 500 })
        )
    }

    @Test
    fun `anyOf with no alternatives is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            updateRestriction<TestUser> { anyOf() }
        }
    }

    // ==================== Composition ====================

    @Test
    fun `combining two restrictions is just All, with no modes to reconcile`() {
        val a = updateRestriction<TestUser> { user -> user.role.cannotBeModified() }
        val b = updateRestriction<TestUser> { user -> user.credits requires (user.role eq Role.Admin) }
        val combined = UpdateRestriction.All(listOf(a, b))

        assertEquals(Condition.Never, combined(modification { it.role assign Role.Admin }))
        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            combined(modification { it.credits assign 5 })
        )
        assertEquals(Condition.Always, combined(modification { it.username assign "newuser" }))
    }

    @Test
    fun `a whitelist combined with a blacklist stays a whitelist`() {
        val whitelist = updateRestriction<TestUser> { user -> onlyModifiable(user.username, user.age) }
        val blacklist = updateRestriction<TestUser> { user -> user.age.cannotBeModified() }
        val combined = UpdateRestriction.All(listOf(whitelist, blacklist))

        assertEquals(Condition.Always, combined(modification { it.username assign "newuser" }))
        assertEquals(Condition.Never, combined(modification { it.age assign 30 }))
        assertEquals(Condition.Never, combined(modification { it.credits assign 5 }))
    }

    @Test
    fun `withAdditionalUpdateRestriction layers onto what the permissions already carry`() {
        val base = ModelPermissions<TestUser>(
            updateRestriction = updateRestriction { user -> user.role.cannotBeModified() }
        )
        val combined = base.withAdditionalUpdateRestriction { user ->
            user.credits requires (user.role eq Role.Admin)
        }

        assertEquals(Condition.Never, combined.updateRestriction(modification { it.role assign Role.Admin }))
        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            combined.updateRestriction(modification { it.credits assign 5 })
        )
        assertEquals(Condition.Always, combined.updateRestriction(modification { it.username assign "newuser" }))
    }

    @Test
    fun `withAdditionalUpdateRestriction keeps an existing whitelist restrictive`() {
        val base = ModelPermissions<TestUser>(
            updateRestriction = updateRestriction { user -> onlyModifiable(user.username, user.age) }
        )
        val combined = base.withAdditionalUpdateRestriction { user -> user.age.cannotBeModified() }

        assertEquals(Condition.Always, combined.updateRestriction(modification { it.username assign "newuser" }))
        assertEquals(Condition.Never, combined.updateRestriction(modification { it.age assign 30 }))
        assertEquals(Condition.Never, combined.updateRestriction(modification { it.credits assign 5 }))
    }

    @Test
    fun `ModelPermissions defaults to unrestricted updates gated only by the update condition`() {
        val permissions = ModelPermissions<TestUser>(update = Condition.Always)
        // `allowed` ANDs the restriction's result with `update` without simplifying, so simplify to compare.
        assertEquals(
            Condition.Always,
            permissions.allowed(modification { it.username assign "newuser" }).simplify()
        )
    }

    // ==================== Fail-closed behavior ====================

    @Test
    fun `a modification the analyzer cannot reason about is denied, not permitted`() {
        val restriction = updateRestriction<TestUser> { user -> user.credits.mustBe { it gte 0 } }

        assertEquals(Condition.Always, restriction(modification { it.credits assign 5 }))
        assertEquals(Condition.Never, restriction(modification { it.credits assign -5 }))
        // An increment depends on the unknown prior value, so it is refused rather than assumed safe.
        assertEquals(Condition.Never, restriction(modification { it.credits plusAssign 1 }))
    }

    @Test
    fun `mustBe is vacuously satisfied by a modification that leaves the field alone`() {
        val restriction = updateRestriction<TestUser> { user -> user.credits.mustBe { it gte 0 } }
        assertEquals(Condition.Always, restriction(modification { it.username assign "newuser" }))
    }

    @Test
    fun `a whole-object write is checked against every field's rules`() {
        val restriction = updateRestriction<TestUser> { user -> user.role.cannotBeModified() }
        assertEquals(Condition.Never, restriction(Modification.Assign(TestUser())))
    }

    // ==================== Real-world scenarios ====================

    @Test
    fun `scenario - user self-service profile updates`() {
        val restriction = updateRestriction<TestUser> { user ->
            onlyModifiable(user.username, user.email, user.age)
            user.age.mustBe { (it gte 13) and (it lt 150) }
        }

        assertEquals(Condition.Always, restriction(modification { it.username assign "newname" }))
        assertEquals(Condition.Always, restriction(modification { it.age assign 30 }))
        assertEquals(Condition.Never, restriction(modification { it.age assign 5 }))
        assertEquals(Condition.Never, restriction(modification { it.role assign Role.Admin }))
        assertEquals(Condition.Never, restriction(modification { it.credits assign 1000 }))
    }

    @Test
    fun `scenario - admins may change most things but never the id`() {
        val restriction = updateRestriction<TestUser> { user ->
            user._id.cannotBeModified()
            user.role requires (user.role eq Role.Admin)
            user.credits requires (user.role eq Role.Admin)
        }
        val adminOnly = condition<TestUser> { it.role eq Role.Admin }

        assertEquals(Condition.Never, restriction(modification { it._id assign Uuid.random() }))
        assertEquals(adminOnly, restriction(modification { it.role assign Role.Moderator }))
        assertEquals(adminOnly, restriction(modification { it.credits assign 1000 }))
        assertEquals(Condition.Always, restriction(modification { it.username assign "newname" }))
    }

    @Test
    fun `scenario - credit system with balance constraints`() {
        val restriction = updateRestriction<TestUser> { user ->
            user.credits requires (user.role eq Role.Admin)
            user.credits.mustBe { it gte 0 }
        }

        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restriction(modification { it.credits assign 500 })
        )
        assertEquals(Condition.Never, restriction(modification { it.credits assign -1 }))
    }

    // ==================== Wire format ====================

    @Test
    fun `round-trips through JSON`() {
        for (restriction in wireFormatCases) {
            val serializer = UpdateRestriction.serializer(TestUser.serializer())
            val json = Json.encodeToString(serializer, restriction)
            assertEquals(restriction, Json.decodeFromString(serializer, json), "Failed round trip for $json")
        }
    }

    @Test
    fun `round-trips through a non-JSON format`() {
        // A binary format, to prove the serializer does not quietly depend on JSON.
        val format = KotlinBytesFormat(EmptySerializersModule())
        val serializer = UpdateRestriction.serializer(TestUser.serializer())

        for (restriction in wireFormatCases) {
            val decoded = format.decodeFromByteArray(serializer, format.encodeToByteArray(serializer, restriction))
            assertEquals(restriction, decoded)
        }
    }

    @Test
    fun `round-trips through the SerializationRegistry`() {
        val restriction = updateRestriction<TestUser> { user ->
            user.credits requires (user.role eq Role.Admin)
        }

        @Suppress("UNCHECKED_CAST")
        val serializer = SerializationRegistry.master[
            UpdateRestriction.serializer(NothingSerializer()).descriptor.serialName,
            arrayOf(TestUser.serializer())
        ] as KSerializer<UpdateRestriction<TestUser>>

        val json = Json.encodeToString(serializer, restriction)
        assertEquals(restriction, Json.decodeFromString(serializer, json))
    }

    @Test
    fun `the wire format names each variant`() {
        val restriction = updateRestriction<TestUser> { user -> user.role.cannotBeModified() }
        val json = Json.encodeToString(UpdateRestriction.serializer(TestUser.serializer()), restriction)
        assertTrue(json.contains("All"), "Expected the top-level All in $json")
        assertTrue(json.contains("Untouched"), "Expected the Untouched leaf in $json")
    }

    @Test
    fun `ModelPermissions round-trips with its restriction`() {
        val permissions = ModelPermissions<TestUser>(
            read = Condition.Always,
            manage = Condition.Always,
            updateRestriction = updateRestriction { user ->
                user.role.cannotBeModified()
                user.credits requires (user.role eq Role.Admin)
            },
        )
        val serializer = ModelPermissions.serializer(TestUser.serializer())

        val json = Json.encodeToString(serializer, permissions)
        assertTrue(json.contains("updateRestriction"), "Expected the renamed key in $json")
        assertEquals(permissions, Json.decodeFromString(serializer, json))
    }

    /**
     * Mirrors how permissions are actually read in production -- every `Json` in this project and in
     * lightning-server sets `ignoreUnknownKeys`, which is what lets the [updateRestriction] rename work as a
     * migration in both directions.
     */
    private val tolerantJson = Json { ignoreUnknownKeys = true }

    @Test
    fun `a payload written against the old updateRestrictions key still reads`() {
        // What a build predating the rename wrote. The old key holds the old per-field shape, which cannot be
        // read as an UpdateRestriction, so the point is that it is skipped and the new field takes its default.
        val oldPayload = """{"read":{"Always":true},"updateRestrictions":{"mode":"Blacklist","fields":[]}}"""
        val decoded = tolerantJson.decodeFromString(ModelPermissions.serializer(TestUser.serializer()), oldPayload)

        assertEquals(Condition.Always, decoded.read)
        assertEquals(UpdateRestriction.unrestricted(), decoded.updateRestriction)
    }

    @Test
    fun `a payload carrying the new key reads for someone who does not know it`() {
        // The mirror image: what an old build does with a new payload. `updateRestriction` is skipped as
        // unknown, so the reader falls back to its own default rather than failing.
        val permissions = ModelPermissions<TestUser>(
            read = Condition.Always,
            manage = Condition.Always,
            updateRestriction = updateRestriction { user -> user.role.cannotBeModified() },
        )
        val serializer = ModelPermissions.serializer(TestUser.serializer())
        val newPayload = Json.encodeToString(serializer, permissions)
        assertTrue(newPayload.contains("updateRestriction"), "Expected the new key in $newPayload")

        // Standing in for a reader whose model lacks the key entirely.
        val asSeenByOldReader = tolerantJson.decodeFromString(
            ModelPermissionsWithoutRestriction.serializer(TestUser.serializer()),
            newPayload,
        )
        assertEquals(Condition.Always, asSeenByOldReader.read)
    }

    // Everything the language can express: blocked fields, conditional fields, value limits, whitelists,
    // alternatives, and nesting.
    private val wireFormatCases: List<UpdateRestriction<TestUser>> = listOf(
        UpdateRestriction.unrestricted(),
        updateRestriction { user ->
            user.role.cannotBeModified()
            user.credits requires (user.role eq Role.Admin)
            user.isActive.mustBe { it eq true }
        },
        updateRestriction { user ->
            onlyModifiable(user.username, user.age)
            anyOf(
                { user.score requires (user.role eq Role.Admin) },
                { user.score.mustBe { it lte 100.0 } },
            )
        },
    )
}
