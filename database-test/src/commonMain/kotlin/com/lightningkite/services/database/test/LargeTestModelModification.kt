package com.lightningkite.services.database.test

import kotlinx.coroutines.flow.*
import com.lightningkite.services.database.*
import com.lightningkite.services.data.*
import com.lightningkite.*
import com.lightningkite.Length.Companion.kilometers
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.*
import kotlin.uuid.*

object LargeTestModelModification {
    class Case(
        val modification: Modification<LargeTestModel>,
        val before: LargeTestModel,
        val after: LargeTestModel,
    )
}