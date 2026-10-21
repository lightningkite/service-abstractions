package com.lightningkite.services.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class UuidV7Test {
    @Test
    fun fromRawAcceptsV7() {
        val uuid = Uuid.generateV7()
        assertEquals(uuid, UuidV7.fromRaw(uuid)?.raw)
    }

    @Test
    fun fromRawRejectsOtherVersions() {
        assertNull(UuidV7.fromRaw(Uuid.random()))
        assertNull(UuidV7.fromRaw(Uuid.NIL))
        assertNull(UuidV7.fromRaw(Uuid.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")))
    }
}
