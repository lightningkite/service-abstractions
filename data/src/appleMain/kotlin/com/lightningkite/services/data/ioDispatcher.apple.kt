package com.lightningkite.services.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Dispatchers.IO is not public API on Native, and there is no server event loop to protect here; Default suffices.
internal actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default
