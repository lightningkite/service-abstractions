package com.lightningkite.services.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val Dispatchers.Io: CoroutineDispatcher get() = Dispatchers.Unconfined