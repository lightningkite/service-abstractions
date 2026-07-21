package com.lightningkite.services.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO
