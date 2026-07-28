package com.lightningkite.services.files

/**
 * Represents a file or directory in a [PublicFileSystem].
 *
 * @see PublicFileSystem
 */
@Deprecated(
    "Use ExternalFile instead. FileObject is now a type alias for ExternalFile for backward compatibility.",
    ReplaceWith("ExternalFile")
)
public typealias FileObject = ExternalFile
