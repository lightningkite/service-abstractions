package com.lightningkite.services.database

import com.lightningkite.services.data.GenerateDataClassPaths
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
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

class UpdateRestrictionsTest {

    // ==================== BLACKLIST MODE TESTS ====================

    @Test
    fun `blacklist mode - default allows all modifications`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            // No restrictions defined
        }

        val emailMod = modification<TestUser> { it.email assign "new@example.com" }
        val roleMod = modification<TestUser> { it.role assign Role.Admin }
        val creditsMod = modification<TestUser> { it.credits assign 100 }

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

        val idMod = modification<TestUser> { it._id assign Uuid.random() }
        val emailMod = modification<TestUser> { it.email assign "new@example.com" }
        val usernameMod = modification<TestUser> { it.username assign "newuser" }

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

        val roleMod = modification<TestUser> { it.role assign Role.Moderator }

        val result = restrictions(roleMod)
        assertEquals(condition<TestUser> { it.role eq Role.Admin }, result)
    }

    @Test
    fun `blacklist mode - requires with multiple conditions`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            user.role requires (user.role eq Role.Admin)
            user.credits requires (user.role eq Role.Admin)
        }

        val roleMod = modification<TestUser> { it.role assign Role.Moderator }
        val creditsMod = modification<TestUser> { it.credits assign 1000 }
        val bothMod = modification<TestUser> {
            it.role assign Role.Moderator
            it.credits assign 1000
        }

        assertEquals(condition<TestUser> { it.role eq Role.Admin }, restrictions(roleMod))
        assertEquals(condition<TestUser> { it.role eq Role.Admin }, restrictions(creditsMod))
        assertEquals(condition<TestUser> { it.role eq Role.Admin }, restrictions(bothMod))
    }

    @Test
    fun `blacklist mode - mustBe restricts target values`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            // Age must be positive
            user.age.mustBe { it gte 0 }
            // Score must be between 0 and 100
            user.score.mustBe { (it gte 0.0) and (it lte 100.0) }
        }

        val ageMod = modification<TestUser> { it.age assign 30 }
        val scoreMod = modification<TestUser> { it.score assign 75.0 }

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

        val creditsMod = modification<TestUser> { it.credits assign 100 }

        val result = restrictions(creditsMod)
        assertEquals(condition<TestUser> { it.role eq Role.Admin }, result)
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

        assertEquals(Condition.Never, restrictions(modification<TestUser> { it._id assign Uuid.random() }))
        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restrictions(modification<TestUser> { it.role assign Role.Moderator })
        )
        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restrictions(modification<TestUser> { it.credits assign 100 })
        )
        assertEquals(Condition.Always, restrictions(modification<TestUser> { it.username assign "newname" }))
    }

    @Test
    fun `blacklist mode - canBeModified is no-op`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            user.email.canBeModified() // This should have no effect in blacklist mode
        }

        val emailMod = modification<TestUser> { it.email assign "new@example.com" }
        assertEquals(Condition.Always, restrictions(emailMod))
    }

    @Test
    fun `blacklist mode - complex condition requirements`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            // Email can only be changed by active admins
            user.email requires ((user.role eq Role.Admin) and (user.isActive eq true))
        }

        val emailMod = modification<TestUser> { it.email assign "new@example.com" }
        val expected = condition<TestUser> { (it.role eq Role.Admin) and (it.isActive eq true) }

        assertEquals(expected, restrictions(emailMod))
    }

    // ==================== WHITELIST MODE TESTS ====================

    @Test
    fun `whitelist mode - default blocks all modifications`() {
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            // No fields explicitly allowed
        }

        val emailMod = modification<TestUser> { it.email assign "new@example.com" }
        val roleMod = modification<TestUser> { it.role assign Role.Admin }
        val creditsMod = modification<TestUser> { it.credits assign 100 }

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

        val emailMod = modification<TestUser> { it.email assign "new@example.com" }
        val usernameMod = modification<TestUser> { it.username assign "newuser" }
        val roleMod = modification<TestUser> { it.role assign Role.Admin }

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

        val emailMod = modification<TestUser> { it.email assign "new@example.com" }
        val usernameMod = modification<TestUser> { it.username assign "newuser" }

        assertEquals(condition<TestUser> { it.isActive eq true }, restrictions(emailMod))
        assertEquals(Condition.Never, restrictions(usernameMod)) // Not whitelisted
    }

    @Test
    fun `whitelist mode - mustBe allows field with value constraint`() {
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            // Age can be modified but must be reasonable
            user.age.mustBe { (it gte 0) and (it lt 150) }
        }

        val ageMod = modification<TestUser> { it.age assign 30 }
        val emailMod = modification<TestUser> { it.email assign "new@example.com" }

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

        val creditsMod = modification<TestUser> { it.credits assign 100 }
        val emailMod = modification<TestUser> { it.email assign "new@example.com" }
        val expected = condition<TestUser> { (it.role eq Role.Moderator) or (it.role eq Role.Admin) }

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

        assertEquals(Condition.Always, restrictions(modification<TestUser> { it.email assign "new@example.com" }))
        assertEquals(Condition.Always, restrictions(modification<TestUser> { it.username assign "newuser" }))
        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restrictions(modification<TestUser> { it.credits assign 100 })
        )
        assertEquals(Condition.Always, restrictions(modification<TestUser> { it.isActive assign true }))
        assertEquals(
            Condition.Never,
            restrictions(modification<TestUser> { it.role assign Role.Admin })
        ) // Not whitelisted
    }

    @Test
    fun `whitelist mode - cannotBeModified is no-op`() {
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            user.email.canBeModified()
            user.role.cannotBeModified() // This should have no effect in whitelist mode
        }

        val emailMod = modification<TestUser> { it.email assign "new@example.com" }
        val roleMod = modification<TestUser> { it.role assign Role.Admin }

        assertEquals(Condition.Always, restrictions(emailMod))
        assertEquals(Condition.Never, restrictions(roleMod)) // Blocked by whitelist, not by cannotBeModified
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

        assertEquals(Condition.Never, extendedRestrictions(modification<TestUser> { it._id assign Uuid.random() }))
        assertEquals(
            Condition.Never,
            extendedRestrictions(modification<TestUser> { it.email assign "new@example.com" })
        )
        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            extendedRestrictions(modification<TestUser> { it.role assign Role.Moderator })
        )
        assertEquals(Condition.Always, extendedRestrictions(modification<TestUser> { it.username assign "newuser" }))
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
            extendedRestrictions(modification<TestUser> { it.email assign "new@example.com" })
        )
        assertEquals(Condition.Always, extendedRestrictions(modification<TestUser> { it.username assign "newuser" }))
        assertEquals(Condition.Always, extendedRestrictions(modification<TestUser> { it.age assign 30 }))
        assertEquals(Condition.Never, extendedRestrictions(modification<TestUser> { it.role assign Role.Admin }))
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

        assertEquals(Condition.Never, combined(modification<TestUser> { it._id assign Uuid.random() }))
        assertEquals(Condition.Never, combined(modification<TestUser> { it.email assign "new@example.com" }))
        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            combined(modification<TestUser> { it.role assign Role.Moderator })
        )
    }

    // ==================== CHAIN MODIFICATION TESTS ====================

    @Test
    fun `blacklist mode - chain modification with multiple restricted fields`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            user.role requires (user.role eq Role.Admin)
            user.credits requires (user.role eq Role.Admin)
        }

        val chainMod = modification<TestUser> {
            it.role assign Role.Moderator
            it.credits assign 1000
        }

        val result = restrictions(chainMod)
        // Both fields require admin, so the condition should be admin
        assertEquals(condition<TestUser> { it.role eq Role.Admin }, result)
    }

    @Test
    fun `blacklist mode - chain modification with mixed restricted and unrestricted fields`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            user.role requires (user.role eq Role.Admin)
        }

        val chainMod = modification<TestUser> {
            it.username assign "newuser"
            it.role assign Role.Moderator
            it.email assign "new@example.com"
        }

        val result = restrictions(chainMod)
        // Only role is restricted
        assertEquals(condition<TestUser> { it.role eq Role.Admin }, result)
    }

    @Test
    fun `whitelist mode - chain modification with allowed fields`() {
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            user.email.canBeModified()
            user.username.canBeModified()
        }

        val chainMod = modification<TestUser> {
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

        val chainMod = modification<TestUser> {
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

        assertEquals(Condition.Always, restrictions(modification<TestUser> { it.email assign "new@example.com" }))
        assertEquals(Condition.Always, restrictions(modification<TestUser> { it.role assign Role.Admin }))
    }

    @Test
    fun `empty restrictions in whitelist mode blocks everything`() {
        val restrictions = UpdateRestrictions<TestUser>(
            mode = UpdateRestrictions.Mode.Whitelist,
            fields = emptyList()
        )

        assertEquals(Condition.Never, restrictions(modification<TestUser> { it.email assign "new@example.com" }))
        assertEquals(Condition.Never, restrictions(modification<TestUser> { it.role assign Role.Admin }))
    }

    @Test
    fun `multiple requires conditions are combined with AND`() {
        val restrictions = updateRestrictions<TestUser> { user ->
            user.role requires (user.isActive eq true)
            user.role requires (user.credits gt 100)
        }

        val roleMod = modification<TestUser> { it.role assign Role.Moderator }
        val result = restrictions(roleMod)

        // Both conditions should be combined
        val expected = condition<TestUser> { (it.isActive eq true) and (it.credits gt 100) }
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
        assertEquals(condition<TestUser> { it.role eq Role.Admin }, result)
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

        assertEquals(Condition.Never, restrictions(modification<TestUser> { it._id assign Uuid.random() }))
        assertEquals(Condition.Never, restrictions(modification<TestUser> { it.role assign Role.Admin }))
        assertEquals(Condition.Never, restrictions(modification<TestUser> { it.credits assign 1000 }))
        assertEquals(
            condition<TestUser> { it._id eq userId },
            restrictions(modification<TestUser> { it.email assign "new@example.com" })
        )
        assertEquals(
            condition<TestUser> { it._id eq userId },
            restrictions(modification<TestUser> { it.username assign "newuser" })
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

        assertEquals(Condition.Never, restrictions(modification<TestUser> { it._id assign Uuid.random() }))
        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restrictions(modification<TestUser> { it.role assign Role.Moderator })
        )
        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restrictions(modification<TestUser> { it.credits assign 1000 })
        )
        assertEquals(
            condition<TestUser> { it.role eq Role.Admin },
            restrictions(modification<TestUser> { it.isActive assign false })
        )
    }

    @Test
    fun `scenario - public API with strict whitelist`() {
        // External API can only update specific safe fields
        val restrictions = updateRestrictions<TestUser>(mode = UpdateRestrictions.Mode.Whitelist) { user ->
            user.username.canBeModified()
            user.age.mustBe { (it gte 0) and (it lt 150) }
        }

        assertEquals(Condition.Always, restrictions(modification<TestUser> { it.username assign "newuser" }))
        assertEquals(Condition.Always, restrictions(modification<TestUser> { it.age assign 30 }))
        assertEquals(Condition.Never, restrictions(modification<TestUser> { it.email assign "new@example.com" }))
        assertEquals(Condition.Never, restrictions(modification<TestUser> { it.role assign Role.Admin }))
        assertEquals(Condition.Never, restrictions(modification<TestUser> { it.credits assign 1000 }))
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

        val creditsMod = modification<TestUser> { it.credits assign 500 }
        assertEquals(condition<TestUser> { it.role eq Role.Admin }, restrictions(creditsMod))
    }
}
