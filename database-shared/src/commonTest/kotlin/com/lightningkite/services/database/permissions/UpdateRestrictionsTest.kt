@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.data.Queryable
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@Queryable
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

@Queryable
data class Span(val start: Int = 0, val endInclusive: Int? = null)

@Queryable
data class Holder(val span: Span = Span(), val thing: Polymorphic = Polymorphic.Foo("a"))

class UpdateRestrictionsTest {

    // ==================== BLACKLIST MODE TESTS ====================

    @Test
    fun `blacklist mode - default allows all modifications`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            // No restrictions defined
        }

        val emailMod = Modification<TestUser> { it.email assign "new@example.com" }
        val roleMod = Modification<TestUser> { it.role assign Role.Admin }
        val creditsMod = Modification<TestUser> { it.credits assign 100 }

        assertEquals(Condition.Always, restrictions(emailMod))
        assertEquals(Condition.Always, restrictions(roleMod))
        assertEquals(Condition.Always, restrictions(creditsMod))
    }

    @Test
    fun `blacklist mode - cannotBeModified blocks field completely`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            user._id.cannotBeModified()
            user.email.cannotBeModified()
        }

        val idMod = Modification<TestUser> { it._id assign Uuid.random() }
        val emailMod = Modification<TestUser> { it.email assign "new@example.com" }
        val usernameMod = Modification<TestUser> { it.username assign "newuser" }

        assertEquals(Condition.Never, restrictions(idMod))
        assertEquals(Condition.Never, restrictions(emailMod))
        assertEquals(Condition.Always, restrictions(usernameMod)) // Not restricted
    }

    @Test
    fun `blacklist mode - requires restricts field with condition`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            // Only admins can modify role
            user.role requires (user.role eq Role.Admin)
        }

        val roleMod = Modification<TestUser> { it.role assign Role.Moderator }

        val result = restrictions(roleMod)
        assertEquals(Condition<TestUser> { it.role eq Role.Admin }, result)
    }

    @Test
    fun `blacklist mode - requires with multiple conditions`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            user.role requires (user.role eq Role.Admin)
            user.credits requires (user.role eq Role.Admin)
        }

        val roleMod = Modification<TestUser> { it.role assign Role.Moderator }
        val creditsMod = Modification<TestUser> { it.credits assign 1000 }
        val bothMod = Modification<TestUser> {
            it.role assign Role.Moderator
            it.credits assign 1000
        }

        assertEquals(Condition<TestUser> { it.role eq Role.Admin }, restrictions(roleMod))
        assertEquals(Condition<TestUser> { it.role eq Role.Admin }, restrictions(creditsMod))
        assertEquals(Condition<TestUser> { it.role eq Role.Admin }, restrictions(bothMod))
    }

    @Test
    fun `blacklist mode - mustBe restricts target values`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            // Age must be positive
            user.age.mustBe { it gte 0 }
            // Score must be between 0 and 100
            user.score.mustBe { (it gte 0.0) and (it lte 100.0) }
        }

        val ageMod = Modification<TestUser> { it.age assign 30 }
        val scoreMod = Modification<TestUser> { it.score assign 75.0 }

        // These should allow modifications (conditions are checked server-side)
        assertEquals(Condition.Always, restrictions(ageMod))
        assertEquals(Condition.Always, restrictions(scoreMod))
    }

    @Test
    fun `blacklist mode - requires with valueMust combines both restrictions`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            // Only admins can change credits, and they must be positive
            user.credits.requires(
                requires = user.role eq Role.Admin,
                valueMust = { it gt 0 }
            )
        }

        val creditsMod = Modification<TestUser> { it.credits assign 100 }

        val result = restrictions(creditsMod)
        assertEquals(Condition<TestUser> { it.role eq Role.Admin }, result)
    }

    @Test
    fun `blacklist mode - multiple fields with different restrictions`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            user._id.cannotBeModified()
            user.role requires (user.role eq Role.Admin)
            user.credits.requires(
                requires = user.role eq Role.Admin,
                valueMust = { it gte 0 }
            )
            user.isActive.mustBe { it eq true }
        }

        assertEquals(Condition.Never, restrictions(Modification<TestUser> { it._id assign Uuid.random() }))
        assertEquals(
            Condition<TestUser> { it.role eq Role.Admin },
            restrictions(Modification<TestUser> { it.role assign Role.Moderator })
        )
        assertEquals(
            Condition<TestUser> { it.role eq Role.Admin },
            restrictions(Modification<TestUser> { it.credits assign 100 })
        )
        assertEquals(Condition.Always, restrictions(Modification<TestUser> { it.username assign "newname" }))
    }

    @Test
    fun `blacklist mode - canBeModified is no-op`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            user.email.canBeModified() // This should have no effect in blacklist mode
        }

        val emailMod = Modification<TestUser> { it.email assign "new@example.com" }
        assertEquals(Condition.Always, restrictions(emailMod))
    }

    @Test
    fun `blacklist mode - complex condition requirements`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            // Email can only be changed by active admins
            user.email requires ((user.role eq Role.Admin) and (user.isActive eq true))
        }

        val emailMod = Modification<TestUser> { it.email assign "new@example.com" }
        val expected = Condition<TestUser> { (it.role eq Role.Admin) and (it.isActive eq true) }

        assertEquals(expected, restrictions(emailMod))
    }

    // ==================== WHITELIST MODE TESTS ====================

    @Test
    fun `whitelist mode - default blocks all modifications`() {
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            // No fields explicitly allowed
        }

        val emailMod = Modification<TestUser> { it.email assign "new@example.com" }
        val roleMod = Modification<TestUser> { it.role assign Role.Admin }
        val creditsMod = Modification<TestUser> { it.credits assign 100 }

        assertEquals(Condition.Never, restrictions(emailMod))
        assertEquals(Condition.Never, restrictions(roleMod))
        assertEquals(Condition.Never, restrictions(creditsMod))
    }

    @Test
    fun `whitelist mode - canBeModified allows field`() {
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            user.email.canBeModified()
            user.username.canBeModified()
        }

        val emailMod = Modification<TestUser> { it.email assign "new@example.com" }
        val usernameMod = Modification<TestUser> { it.username assign "newuser" }
        val roleMod = Modification<TestUser> { it.role assign Role.Admin }

        assertEquals(Condition.Always, restrictions(emailMod))
        assertEquals(Condition.Always, restrictions(usernameMod))
        assertEquals(Condition.Never, restrictions(roleMod)) // Not whitelisted
    }

    @Test
    fun `whitelist mode - requires allows field with condition`() {
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            // Email can be modified if user is active
            user.email requires (user.isActive eq true)
        }

        val emailMod = Modification<TestUser> { it.email assign "new@example.com" }
        val usernameMod = Modification<TestUser> { it.username assign "newuser" }

        assertEquals(Condition<TestUser> { it.isActive eq true }, restrictions(emailMod))
        assertEquals(Condition.Never, restrictions(usernameMod)) // Not whitelisted
    }

    @Test
    fun `whitelist mode - mustBe allows field with value constraint`() {
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            // Age can be modified but must be reasonable
            user.age.mustBe { (it gte 0) and (it lt 150) }
        }

        val ageMod = Modification<TestUser> { it.age assign 30 }
        val emailMod = Modification<TestUser> { it.email assign "new@example.com" }

        assertEquals(Condition.Always, restrictions(ageMod))
        assertEquals(Condition.Never, restrictions(emailMod)) // Not whitelisted
    }

    @Test
    fun `whitelist mode - requires with valueMust combines both`() {
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            // Credits can be modified by moderators or admins, but only to positive values
            user.credits.requires(
                requires = (user.role eq Role.Moderator) or (user.role eq Role.Admin),
                valueMust = { it gte 0 }
            )
        }

        val creditsMod = Modification<TestUser> { it.credits assign 100 }
        val emailMod = Modification<TestUser> { it.email assign "new@example.com" }
        val expected = Condition<TestUser> { (it.role eq Role.Moderator) or (it.role eq Role.Admin) }

        assertEquals(expected, restrictions(creditsMod))
        assertEquals(Condition.Never, restrictions(emailMod)) // Not whitelisted
    }

    @Test
    fun `whitelist mode - multiple allowed fields with mixed restrictions`() {
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            user.email.canBeModified()
            user.username.canBeModified()
            user.credits requires (user.role eq Role.Admin)
            user.isActive.mustBe { it eq true }
        }

        assertEquals(Condition.Always, restrictions(Modification<TestUser> { it.email assign "new@example.com" }))
        assertEquals(Condition.Always, restrictions(Modification<TestUser> { it.username assign "newuser" }))
        assertEquals(
            Condition<TestUser> { it.role eq Role.Admin },
            restrictions(Modification<TestUser> { it.credits assign 100 })
        )
        assertEquals(Condition.Always, restrictions(Modification<TestUser> { it.isActive assign true }))
        assertEquals(
            Condition.Never,
            restrictions(Modification<TestUser> { it.role assign Role.Admin })
        ) // Not whitelisted
    }

    @Test
    fun `whitelist mode - cannotBeModified on an unlisted field still blocks it`() {
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            user.email.canBeModified()
            user.role.cannotBeModified()
        }

        assertEquals(Condition.Always, restrictions(Modification<TestUser> { it.email assign "new@example.com" }))
        assertEquals(Condition.Never, restrictions(Modification<TestUser> { it.role assign Role.Admin }))
    }

    // ==================== COMPOSITION TESTS ====================

    @Test
    fun `include merges restrictions from another instance`() {
        val baseRestrictions = updateRestrictions<TestUser> { user ->
            user._id.cannotBeModified()
            user.email.cannotBeModified()
        }

        val extendedRestrictions = updateRestrictions<TestUser> { user ->
            include(baseRestrictions)
            user.role requires (user.role eq Role.Admin)
        }

        assertEquals(Condition.Never, extendedRestrictions(Modification<TestUser> { it._id assign Uuid.random() }))
        assertEquals(
            Condition.Never,
            extendedRestrictions(Modification<TestUser> { it.email assign "new@example.com" })
        )
        assertEquals(
            Condition<TestUser> { it.role eq Role.Admin },
            extendedRestrictions(Modification<TestUser> { it.role assign Role.Moderator })
        )
        assertEquals(Condition.Always, extendedRestrictions(Modification<TestUser> { it.username assign "newuser" }))
    }

    @Test
    fun `include works with whitelist mode`() {
        val baseRestrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            user.email.canBeModified()
            user.username.canBeModified()
        }

        val extendedRestrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            include(baseRestrictions)
            user.age.canBeModified()
        }

        assertEquals(
            Condition.Always,
            extendedRestrictions(Modification<TestUser> { it.email assign "new@example.com" })
        )
        assertEquals(Condition.Always, extendedRestrictions(Modification<TestUser> { it.username assign "newuser" }))
        assertEquals(Condition.Always, extendedRestrictions(Modification<TestUser> { it.age assign 30 }))
        assertEquals(Condition.Never, extendedRestrictions(Modification<TestUser> { it.role assign Role.Admin }))
    }

    @Test
    fun `multiple includes accumulate restrictions`() {
        val restrictions1 = updateRestrictions<TestUser> { user ->
            user._id.cannotBeModified()
        }

        val restrictions2 = updateRestrictions<TestUser> { user ->
            user.email.cannotBeModified()
        }

        val combined = updateRestrictions<TestUser> { user ->
            include(restrictions1)
            include(restrictions2)
            user.role requires (user.role eq Role.Admin)
        }

        assertEquals(Condition.Never, combined(Modification<TestUser> { it._id assign Uuid.random() }))
        assertEquals(Condition.Never, combined(Modification<TestUser> { it.email assign "new@example.com" }))
        assertEquals(
            Condition<TestUser> { it.role eq Role.Admin },
            combined(Modification<TestUser> { it.role assign Role.Moderator })
        )
    }

    // ==================== CHAIN MODIFICATION TESTS ====================

    @Test
    fun `blacklist mode - chain modification with multiple restricted fields`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            user.role requires (user.role eq Role.Admin)
            user.credits requires (user.role eq Role.Admin)
        }

        val chainMod = Modification<TestUser> {
            it.role assign Role.Moderator
            it.credits assign 1000
        }

        val result = restrictions(chainMod)
        // Both fields require admin, so the condition should be admin
        assertEquals(Condition<TestUser> { it.role eq Role.Admin }, result)
    }

    @Test
    fun `blacklist mode - chain modification with mixed restricted and unrestricted fields`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            user.role requires (user.role eq Role.Admin)
        }

        val chainMod = Modification<TestUser> {
            it.username assign "newuser"
            it.role assign Role.Moderator
            it.email assign "new@example.com"
        }

        val result = restrictions(chainMod)
        // Only role is restricted
        assertEquals(Condition<TestUser> { it.role eq Role.Admin }, result)
    }

    @Test
    fun `whitelist mode - chain modification with allowed fields`() {
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            user.email.canBeModified()
            user.username.canBeModified()
        }

        val chainMod = Modification<TestUser> {
            it.email assign "new@example.com"
            it.username assign "newuser"
        }

        val result = restrictions(chainMod)
        assertEquals(Condition.Always, result)
    }

    @Test
    fun `whitelist mode - chain modification with blocked field fails`() {
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            user.email.canBeModified()
        }

        val chainMod = Modification<TestUser> {
            it.email assign "new@example.com"
            it.role assign Role.Admin // Not whitelisted
        }

        val result = restrictions(chainMod)
        assertEquals(Condition.Never, result)
    }

    // ==================== EDGE CASES ====================

    @Test
    fun `empty restrictions in blacklist mode allows everything`() {
        val restrictions = UpdateRestrictions<TestUser>(
            mode = UpdateRestrictions.Mode.Blacklist,
            fields = emptyList()
        )

        assertEquals(Condition.Always, restrictions(Modification<TestUser> { it.email assign "new@example.com" }))
        assertEquals(Condition.Always, restrictions(Modification<TestUser> { it.role assign Role.Admin }))
    }

    @Test
    fun `empty restrictions in whitelist mode blocks everything`() {
        val restrictions = UpdateRestrictions<TestUser>(
            mode = UpdateRestrictions.Mode.Whitelist,
            fields = emptyList()
        )

        assertEquals(Condition.Never, restrictions(Modification<TestUser> { it.email assign "new@example.com" }))
        assertEquals(Condition.Never, restrictions(Modification<TestUser> { it.role assign Role.Admin }))
    }

    @Test
    fun `multiple requires conditions are combined with AND`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            user.role requires (user.isActive eq true)
            user.role requires (user.credits gt 100)
        }

        val roleMod = Modification<TestUser> { it.role assign Role.Moderator }
        val result = restrictions(roleMod)

        // Both conditions should be combined
        val expected = Condition<TestUser> { (it.isActive eq true) and (it.credits gt 100) }
        assertEquals(expected, result)
    }

    @Test
    fun `restrictions work with assign modification`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            user.email requires (user.role eq Role.Admin)
        }

        val assignMod = Modification.Assign(TestUser(email = "new@example.com"))
        // Assign modifies all fields, so email restriction should apply
        val result = restrictions(assignMod)
        assertEquals(Condition<TestUser> { it.role eq Role.Admin }, result)
    }

    // ==================== REAL-WORLD SCENARIOS ====================

    @Test
    fun `scenario - user self-service profile updates`() {
        val userId = Uuid.random()

        // Users can update their own profile fields, but not sensitive ones
        val restrictions = updateRestrictions<TestUser> { user ->
            // Can't change ID, role, or credits
            user._id.cannotBeModified()
            user.role.cannotBeModified()
            user.credits.cannotBeModified()

            // Can update email/username only for their own account
            user.email requires (user._id eq userId)
            user.username requires (user._id eq userId)
        }

        assertEquals(Condition.Never, restrictions(Modification<TestUser> { it._id assign Uuid.random() }))
        assertEquals(Condition.Never, restrictions(Modification<TestUser> { it.role assign Role.Admin }))
        assertEquals(Condition.Never, restrictions(Modification<TestUser> { it.credits assign 1000 }))
        assertEquals(
            Condition<TestUser> { it._id eq userId },
            restrictions(Modification<TestUser> { it.email assign "new@example.com" })
        )
        assertEquals(
            Condition<TestUser> { it._id eq userId },
            restrictions(Modification<TestUser> { it.username assign "newuser" })
        )
    }

    @Test
    fun `scenario - admin can modify most fields`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            // Even admins can't change IDs
            user._id.cannotBeModified()

            // Most other fields require admin
            user.role requires (user.role eq Role.Admin)
            user.credits requires (user.role eq Role.Admin)
            user.isActive requires (user.role eq Role.Admin)
        }

        assertEquals(Condition.Never, restrictions(Modification<TestUser> { it._id assign Uuid.random() }))
        assertEquals(
            Condition<TestUser> { it.role eq Role.Admin },
            restrictions(Modification<TestUser> { it.role assign Role.Moderator })
        )
        assertEquals(
            Condition<TestUser> { it.role eq Role.Admin },
            restrictions(Modification<TestUser> { it.credits assign 1000 })
        )
        assertEquals(
            Condition<TestUser> { it.role eq Role.Admin },
            restrictions(Modification<TestUser> { it.isActive assign false })
        )
    }

    @Test
    fun `scenario - public API with strict whitelist`() {
        // External API can only update specific safe fields
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            user.username.canBeModified()
            user.age.mustBe { (it gte 0) and (it lt 150) }
        }

        assertEquals(Condition.Always, restrictions(Modification<TestUser> { it.username assign "newuser" }))
        assertEquals(Condition.Always, restrictions(Modification<TestUser> { it.age assign 30 }))
        assertEquals(Condition.Never, restrictions(Modification<TestUser> { it.email assign "new@example.com" }))
        assertEquals(Condition.Never, restrictions(Modification<TestUser> { it.role assign Role.Admin }))
        assertEquals(Condition.Never, restrictions(Modification<TestUser> { it.credits assign 1000 }))
    }

    @Test
    fun `scenario - credit system with balance constraints`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            // Credits can only be modified by admins
            // And must always be non-negative
            user.credits.requires(
                requires = user.role eq Role.Admin,
                valueMust = { it gte 0 }
            )
        }

        val creditsMod = Modification<TestUser> { it.credits assign 500 }
        assertEquals(Condition<TestUser> { it.role eq Role.Admin }, restrictions(creditsMod))
    }

    // ==================== PARENT / CHILD PATHS ====================

    private fun <T> assertNever(restrictions: UpdateRestrictions<T>, modification: Modification<T>) =
        assertEquals(Condition.Never, restrictions(modification), "$modification should be refused")

    @Test
    fun `whitelist - a rule on a child does not allow writing the parent`() {
        val restrictions = whitelistRestrictions<LargeTestModel> {
            it.embedded.value1.canBeModified()
            it.embeddedNullable.notNull.value1.canBeModified()
        }
        val writeChildThenParent = path<LargeTestModel>().embedded.mapModification(
            Modification.Chain(
                listOf(
                    path<ClassUsedForEmbedding>().value1.mapModification(Modification.Assign("x")),
                    Modification.Assign(ClassUsedForEmbedding("y", 99)),
                )
            )
        )

        assertEquals(Condition.Always, restrictions(Modification { it.embedded.value1 assign "x" }))
        assertEquals(Condition.Always, restrictions(Modification { it.embeddedNullable.notNull.value1 assign "x" }))
        assertNever(restrictions, Modification { it.embedded assign ClassUsedForEmbedding("x", 99) })
        assertNever(restrictions, Modification { it.embeddedNullable assign ClassUsedForEmbedding("x", 5) })
        assertNever(restrictions, Modification { it.embeddedNullable assign null })
        assertNever(restrictions, writeChildThenParent)
        assertNever(restrictions, Modification { it.embedded.value2 assign 99 })
    }

    @Test
    fun `whitelist - a rule on a parent allows writing its children`() {
        val restrictions = whitelistRestrictions<LargeTestModel> { it.embedded.canBeModified() }

        assertEquals(Condition.Always, restrictions(Modification { it.embedded.value1 assign "x" }))
        assertEquals(Condition.Always, restrictions(Modification { it.embedded assign ClassUsedForEmbedding() }))
        assertNever(restrictions, Modification { it.int assign 1 })
    }

    @Test
    fun `whitelist - a rule on list elements' field does not allow rewriting whole elements`() {
        val restrictions = whitelistRestrictions<LargeTestModel> { it.listEmbedded.elements.value2.canBeModified() }

        assertEquals(Condition.Always, restrictions(Modification { it.listEmbedded.forEach { it.value2 assign 3 } }))
        assertNever(restrictions, Modification { it.listEmbedded.forEach { it.value1 assign "x" } })
        assertNever(restrictions, Modification { it.listEmbedded.forEach { it assign ClassUsedForEmbedding() } })
        assertNever(restrictions, Modification { it.listEmbedded assign listOf() })
        assertNever(restrictions, Modification { it.listEmbedded += ClassUsedForEmbedding() })
        assertNever(restrictions, Modification { it.listEmbedded.removeAll { it.value2 eq 1 } })
    }

    @Test
    fun `whitelist - writes through a sealed variant use the variant's field path`() {
        val restrictions = whitelistRestrictions<Holder> { it.thing.asFoo.name.canBeModified() }

        assertEquals(Condition.Always, restrictions(Modification { it.thing.asFoo.name assign "b" }))
        assertNever(restrictions, Modification { it.thing.asFoo assign Polymorphic.Foo("b") })
        assertNever(restrictions, Modification { it.thing assign Polymorphic.Bar(1) })
    }

    @Test
    fun `whitelist - a root assign is refused unless the root is allowed`() {
        val restrictions = whitelistRestrictions<TestUser> { it.email.canBeModified() }
        assertNever(restrictions, Modification.Assign(TestUser()))
    }

    @Test
    fun `whitelist - cannotBeModified carves a field out of an allowed parent`() {
        val restrictions = whitelistRestrictions<LargeTestModel> {
            it.embedded.canBeModified()
            it.embedded.value1.cannotBeModified()
        }

        assertEquals(Condition.Always, restrictions(Modification { it.embedded.value2 assign 3 }))
        assertNever(restrictions, Modification { it.embedded.value1 assign "x" })
        assertNever(restrictions, Modification { it.embedded assign ClassUsedForEmbedding() })
    }

    @Test
    fun `whitelist - a rule on a child applies when writing its allowed parent`() {
        val admin = Condition<LargeTestModel> { it.boolean eq true }
        val restrictions = whitelistRestrictions<LargeTestModel> {
            it.embedded.canBeModified()
            it.embedded.value2 requires admin
        }

        assertEquals(Condition.Always, restrictions(Modification { it.embedded.value1 assign "x" }))
        assertEquals(admin, restrictions(Modification { it.embedded assign ClassUsedForEmbedding() }))
    }

    @Test
    fun `whitelist - nullable end date that must be in the future or open`() {
        val admin = Condition<Holder> { it.thing.asFoo.name eq "admin" }
        val restrictions = whitelistRestrictions<Holder> {
            it.span.endInclusive.requires(admin) { it.notNull.gte(10) or it.eq(null) }
        }

        assertEquals(admin, restrictions(Modification { it.span.endInclusive assign 12 }))
        assertEquals(admin, restrictions(Modification { it.span.endInclusive assign null }))
        assertNever(restrictions, Modification { it.span.endInclusive assign 5 })
        assertNever(restrictions, Modification { it.span assign Span(0, 12) })
        assertNever(restrictions, Modification { it.span.start assign 1 })
    }

    // ==================== VALUE CONSTRAINTS ON SUB-FIELD WRITES ====================

    @Test
    fun `mustBe with And on a parent checks the written child`() {
        val restrictions = updateRestrictions<LargeTestModel> {
            it.embedded.mustBe { (it.value1 eq "CO") and (it.value2 neq 0) }
        }

        assertNever(restrictions, Modification { it.embedded.value1 assign "TX" })
        assertNever(restrictions, Modification { it.embedded.value2 assign 0 })
        assertNever(restrictions, Modification { it.embedded assign ClassUsedForEmbedding("TX", 1) })
        // The part a write leaves alone must already hold, so a write can fix one part of a row that breaks the rule.
        assertEquals(Condition { it.embedded.value2 neq 0 }, restrictions(Modification { it.embedded.value1 assign "CO" }))
        assertEquals(Condition { it.embedded.value1 eq "CO" }, restrictions(Modification { it.embedded.value2 assign 5 }))
        assertEquals(Condition.Always, restrictions(Modification { it.embedded assign ClassUsedForEmbedding("CO", 1) }))
    }

    @Test
    fun `mustBe with Or on a parent refuses child writes it cannot prove`() {
        val restrictions = updateRestrictions<LargeTestModel> {
            it.embedded.mustBe { (it.value1 eq "CO") or (it.value2 eq 0) }
        }

        assertNever(restrictions, Modification { it.embedded.value1 assign "TX" })
        assertNever(restrictions, Modification { it.embedded.value1 assign "CO" })
        assertEquals(Condition.Always, restrictions(Modification { it.embedded assign ClassUsedForEmbedding("TX", 0) }))
    }

    @Test
    fun `mustBe on a nullable object checks writes inside it`() {
        val restrictions = updateRestrictions<LargeTestModel> {
            it.embeddedNullable.notNull.mustBe { it.value1 eq "CO" }
        }

        assertNever(restrictions, Modification { it.embeddedNullable.notNull.value1 assign "TX" })
        assertEquals(Condition { it.embeddedNullable neq null }, restrictions(Modification { it.embeddedNullable.notNull.value1 assign "CO" }))
        assertEquals(Condition { it.embeddedNullable.notNull.value1 eq "CO" }, restrictions(Modification { it.embeddedNullable.notNull.value2 assign 3 }))
    }

    @Test
    fun `guaranteedAfter fails closed on shapes it cannot prove`() {
        val embeddedValue1 = Modification<LargeTestModel> { it.embedded.value1 assign "x" }
        assertEquals(false, Condition.Never.guaranteedAfter(embeddedValue1))
        assertEquals(false, Condition.Not(Condition<LargeTestModel> { it.embedded.value1 neq "x" }).guaranteedAfter(embeddedValue1))
        assertEquals(true, Condition<LargeTestModel> { it.embedded.value1 eq "x" }.guaranteedAfter(embeddedValue1))
        assertEquals(true, Condition<LargeTestModel> { it.embedded.value2 eq 1 }.guaranteedAfter(embeddedValue1))
    }

    // ==================== asType AND Nothing ====================

    @Test
    fun `affects descends asType and ignores Nothing`() {
        val fooName = Modification<Holder> { it.thing.asFoo.name assign "b" }
        val nothing = Modification.Nothing.invoke<Holder>()

        assertTrue(fooName.affects(path<Holder>().thing))
        assertTrue(fooName.affects(path<Holder>().thing.asFoo.name))
        assertFalse(fooName.affects(path<Holder>().thing.asBar.id))
        assertFalse(fooName.affects(path<Holder>().span))
        assertTrue(Modification<Holder> { it.thing assign Polymorphic.Bar(1) }.affects(path<Holder>().thing.asFoo.name))
        assertFalse(nothing.affects(path<Holder>()))
        assertFalse(nothing.affects(path<Holder>().span))
        assertFalse(Modification<Holder> { it.span.start assign 1 }.let { Modification.Chain(listOf(nothing, it)) }.affects(path<Holder>().thing))
    }

    @Test
    fun `whitelist - writes through asType and Nothing no longer trip unrelated rules`() {
        val restrictions = whitelistRestrictions<Holder> {
            it.thing.asFoo.name.canBeModified()
            it.thing.asBar.id.cannotBeModified()
        }

        assertEquals(Condition.Always, restrictions(Modification { it.thing.asFoo.name assign "b" }))
        assertEquals(Condition.Always, restrictions(Modification.Nothing.invoke()))
        assertNever(restrictions, Modification { it.thing.asBar.id assign 2 })
    }

    @Test
    fun `a mask under one variant restricts only reads of that variant's fields`() {
        val admin = Condition<Holder> { it.span.start eq 1 }
        val hidden = mask<Holder> { it.thing.asFoo.name.mask("", unless = admin) }

        assertEquals(Condition.Always, hidden(Condition { it.thing.asBar.id eq 1 }))
        assertEquals(admin, hidden(Condition { it.thing.asFoo.name eq "x" }))
        assertEquals(admin, hidden(Condition { it.thing eq Polymorphic.Foo("x") }))
        assertEquals(Condition.Always, hidden.permitSort(listOf(SortPart(path<Holder>().thing.asBar.id))))
        assertEquals(admin, hidden.permitSort(listOf(SortPart(path<Holder>().thing.asFoo.name))))
        assertEquals(admin, hidden(path<Holder>().thing))
        assertEquals(Condition.Always, mask<Holder> { always(Modification.Nothing.invoke()) }(Condition { it.span.start eq 1 }))
    }

    // ==================== guaranteedAfter OVER UNREAD PARTS ====================

    @Test
    fun `neq null on an object survives writes inside it but not assigning null`() {
        val restrictions = updateRestrictions<LargeTestModel> { it.embeddedNullable.mustBe { it neq null } }

        val nonNull = Condition<LargeTestModel> { it.embeddedNullable neq null }
        assertEquals(nonNull, restrictions(Modification { it.embeddedNullable.notNull.value1 assign "x" }))
        assertEquals(nonNull, restrictions(Modification { it.embeddedNullable.notNull assign ClassUsedForEmbedding() }))
        assertEquals(Condition.Always, restrictions(Modification { it.embeddedNullable assign ClassUsedForEmbedding() }))
        assertNever(restrictions, Modification { it.embeddedNullable assign null })
    }

    @Test
    fun `eq null on an object still refuses writes inside it`() {
        val restrictions = updateRestrictions<LargeTestModel> { it.embeddedNullable.mustBe { it eq null } }

        assertNever(restrictions, Modification { it.embeddedNullable.notNull.value1 assign "x" })
        assertEquals(Condition.Always, restrictions(Modification { it.embeddedNullable assign null }))
    }

    @Test
    fun `Or and Not rules allow writes to fields they do not read`() {
        val or = updateRestrictions<LargeTestModel> { it.embedded.mustBe { (it.value1 eq "CO") or (it.value1 eq "TX") } }
        val not = updateRestrictions<LargeTestModel> { it.embedded.mustBe { !(it.value1 eq "x") } }

        assertEquals(or.fields.single().limitedTo, or(Modification { it.embedded.value2 assign 5 }))
        assertNever(or, Modification { it.embedded.value1 assign "CO" })
        assertEquals(not.fields.single().limitedTo, not(Modification { it.embedded.value2 assign 5 }))
        assertNever(not, Modification { it.embedded.value1 assign "y" })
    }

    // ==================== RULES A WRITE LEAVES ALONE ====================

    @Test
    fun `a rule pinning one variant is checked before writes under another`() {
        val restrictions = updateRestrictions<Holder> { it.thing.mustBe { it.asFoo.name eq "a" } }
        val writeBar = restrictions(Modification { it.thing.asBar.id assign 999 })

        assertTrue(writeBar(Holder(thing = Polymorphic.Foo("a"))))
        assertFalse(writeBar(Holder(thing = Polymorphic.Bar(1))))
        assertNever(restrictions, Modification { it.thing assign Polymorphic.Bar(1) })
        assertEquals(Condition.Always, restrictions(Modification { it.thing assign Polymorphic.Foo("a") }))
    }

    @Test
    fun `writes under asType require the variant`() {
        val restrictions = updateRestrictions<Holder> { it.thing.mustBe { it.asFoo.name neq "" } }
        val writeName = restrictions(Modification { it.thing.asFoo.name assign "b" })

        assertTrue(writeName(Holder(thing = Polymorphic.Foo(""))))
        assertFalse(writeName(Holder(thing = Polymorphic.Bar(1))))
        assertNever(restrictions, Modification { it.thing.asFoo.name assign "" })
    }

    @Test
    fun `a chain is checked last write first`() {
        val restrictions = updateRestrictions<LargeTestModel> {
            it.embedded.mustBe { (it.value1 eq "CO") and (it.value2 neq 0) }
        }

        assertEquals(Condition.Always, restrictions(Modification {
            it.embedded.value1 assign "CO"
            it.embedded.value2 assign 5
        }))
        assertNever(restrictions, Modification {
            it.embedded.value1 assign "CO"
            it.embedded.value1 assign "TX"
        })
        assertEquals(Condition { it.embedded.value2 neq 0 }, restrictions(Modification {
            it.embedded.value1 assign "TX"
            it.embedded.value1 assign "CO"
        }))
    }

    @Test
    fun `forEachIf keeps skipped elements under the rule`() {
        val restrictions = updateRestrictions<LargeTestModel> { it.listEmbedded.mustBe { it.all { it.value2 gt 2 } } }

        assertEquals(Condition.Always, restrictions(Modification { it.listEmbedded.forEach { it.value2 assign 3 } }))
        assertEquals(
            restrictions.fields.single().limitedTo,
            restrictions(Modification { it.listEmbedded.forEachIf({ it.value1 eq "x" }) { it.value2 assign 3 } })
        )
        assertNever(restrictions, Modification { it.listEmbedded.forEach { it.value2 assign 1 } })
    }
}
