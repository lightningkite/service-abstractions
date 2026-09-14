package com.lightningkite.services.pubsub

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.pubsub.test.PubSubTest

class DebugPubSubTest : PubSubTest() {
    override val pubsub: PubSub = DebugPubSub("test", TestSettingContext())
}
