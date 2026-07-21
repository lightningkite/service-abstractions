package com.lightningkite.services.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// JS is single-threaded with no blocking I/O to offload; Default is a harmless no-op-equivalent here.
internal actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default
