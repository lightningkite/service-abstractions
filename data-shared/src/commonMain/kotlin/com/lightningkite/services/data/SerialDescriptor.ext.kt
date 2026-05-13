package com.lightningkite.services.data

import kotlinx.serialization.descriptors.SerialDescriptor

public fun SerialDescriptor.serialNameFQN(): String = serialName.substringBefore("/")