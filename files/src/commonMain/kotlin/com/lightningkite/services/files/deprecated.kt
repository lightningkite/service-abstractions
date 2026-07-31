package com.lightningkite.services.files

@Deprecated(
    "Renamed to ExternalFileSystem",
    ReplaceWith("ExternalFileSystem", "com.lightningkite.services.files.ExternalFileSystem")
)
public typealias PublicFileSystem = ExternalFileSystem


@Deprecated(
    "Use ExternalFile instead. FileObject is now a type alias for ExternalFile for backward compatibility.",
    ReplaceWith("ExternalFile", "com.lightningkite.services.files.ExternalFile")
)
public typealias FileObject = ExternalFile

