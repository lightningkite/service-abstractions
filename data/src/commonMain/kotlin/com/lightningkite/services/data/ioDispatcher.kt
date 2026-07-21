package com.lightningkite.services.data

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher used to offload blocking I/O off the calling thread.
 *
 * On the JVM/Android this is `Dispatchers.IO` — a real thread pool for blocking work, so consuming a blocking
 * [Data.Source]/[Data.Sink] can never stall an engine's event loop. On JS (no threads) and Native (no server event
 * loop, and `Dispatchers.IO` is not public API there) it falls back to `Dispatchers.Default`.
 */
internal expect val ioDispatcher: CoroutineDispatcher
