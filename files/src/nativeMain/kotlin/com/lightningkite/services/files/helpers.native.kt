package com.lightningkite.services.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal actual val Dispatchers.Io: CoroutineDispatcher get() = Dispatchers.IO