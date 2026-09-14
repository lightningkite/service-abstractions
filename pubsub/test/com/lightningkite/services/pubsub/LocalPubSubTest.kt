package com.lightningkite.services.pubsub

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.pubsub.test.PubSubTest

class LocalPubSubTest : PubSubTest() {
    override val pubsub: PubSub = LocalPubSub("test", TestSettingContext())
}
