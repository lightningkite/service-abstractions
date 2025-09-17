package com.lightningkite.services.files.s3

import kotlinx.io.RawSource
import kotlinx.io.asSource
import java.io.InputStream

/**
 * Converts a Java InputStream to a kotlinx.io.RawSource.
 */
public fun InputStream.toSource(): RawSource = this.asSource()