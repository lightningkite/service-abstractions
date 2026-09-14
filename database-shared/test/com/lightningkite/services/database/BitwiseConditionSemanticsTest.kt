package com.lightningkite.services.database

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the meaning of the four bitwise conditions against a plainly-stated definition, over the
 * whole 32-bit range including the sign bit.
 *
 * The per-engine conformance tests compare each driver against `Condition.invoke`, so they can only
 * catch a driver that disagrees with the reference — not a reference that is itself wrong. That is
 * how `IntBitsAnySet` shipped as `on and mask > 0`: correct for every small mask anyone had tried,
 * and wrong for any mask containing bit 31, which is negative as a signed Int.
 */
class BitwiseConditionSemanticsTest {

    private val signBit = 1 shl 31

    private val values = listOf(
        0,
        0b0001,
        0b0011,
        0b0101,
        0b1111,
        signBit,
        signBit or 0b0001,
        -1,
    )

    private val masks = listOf(
        0b0001,
        0b0011,
        0b0101,
        signBit,
        signBit or 0b0001,
        -1,
    )

    private fun check(name: String, condition: (Int) -> Condition<Int>, expected: (on: Int, mask: Int) -> Boolean) {
        for (mask in masks) {
            for (on in values) {
                assertEquals(
                    expected(on, mask),
                    condition(mask)(on),
                    "$name(mask=${mask.toHexString()}) on ${on.toHexString()}",
                )
            }
        }
    }

    private fun Int.toHexString(): String = "0x" + toUInt().toString(16).padStart(8, '0')

    @Test
    fun `allClear is true when the value shares no bit with the mask`() =
        check("allClear", { Condition.IntBitsClear(it) }) { on, mask -> on and mask == 0 }

    @Test
    fun `allSet is true when the value carries every bit of the mask`() =
        check("allSet", { Condition.IntBitsSet(it) }) { on, mask -> on and mask == mask }

    @Test
    fun `anyClear is the exact negation of allSet`() =
        check("anyClear", { Condition.IntBitsAnyClear(it) }) { on, mask -> on and mask != mask }

    @Test
    fun `anySet is the exact negation of allClear`() =
        check("anySet", { Condition.IntBitsAnySet(it) }) { on, mask -> on and mask != 0 }

    /**
     * The specific case that was wrong, called out so a regression names itself.
     */
    @Test
    fun `the sign bit behaves like every other bit`() {
        assertEquals(true, Condition.IntBitsAnySet(signBit)(signBit))
        assertEquals(false, Condition.IntBitsAnySet(signBit)(0))
        assertEquals(true, Condition.IntBitsAnyClear(signBit)(0))
        assertEquals(false, Condition.IntBitsAnyClear(signBit)(signBit))
    }
}
