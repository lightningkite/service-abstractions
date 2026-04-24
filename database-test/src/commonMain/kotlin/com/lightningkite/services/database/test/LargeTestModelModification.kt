package com.lightningkite.services.database.test

import com.lightningkite.services.database.Modification

object LargeTestModelModification {
    class Case(
        val modification: Modification<LargeTestModel>,
        val before: LargeTestModel,
        val after: LargeTestModel,
    )
}