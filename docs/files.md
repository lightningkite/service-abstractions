# File System Abstraction

The `files` module provides an abstraction for working with file storage systems across different backends (local
filesystem, S3, Azure Blob) using a consistent API.

## Overview

- **`ExternalFileSystem`** - The service: every file operation, keyed by `ExternalPath`.  `PublicFileSystem` is a
  deprecated alias for it.
- **`ExternalFile`** - A `(fileSystem, path)` pair whose operations delegate to the file system.  `FileObject` is a
  deprecated alias for it.
- **`ExternalPath`** - A path as a list of literal segments, with a conservative string form for URLs and stored
  references.
- **`FileInfo`** - Metadata about a file (type, size, last modified)
- **`FileScanner`** - Service for validating uploaded files

## Quick Start

### Setting Up a File System

```kotlin
import com.lightningkite.services.files.ExternalFileSystem

val fileSystem = ExternalFileSystem.Settings(
    url = "file:///path/to/storage?serveUrl=http://localhost:8080/files"
).invoke("my-files", settingContext)
```

### Basic File Operations

```kotlin
val file = fileSystem.root.then("uploads/photo.jpg")

file.put(TypedData.bytes(imageBytes, MediaType.Image.JPEG))

val data: TypedData? = file.get()                 // null if not found; close it when done
val header: TypedData? = file.getRange(0L..15L)   // inclusive bounds, clamped to the file's length

val info: FileInfo? = file.head()
println("Size: ${info?.size}, Type: ${info?.type}")

file.delete()
```

### Working with Directories

```kotlin
val userDir = fileSystem.root.then("uploads", "user-123")

val files: List<ExternalFile> = userDir.list() // empty when there is nothing there
files.forEach { println(it.name) }

// Parent directories are created as needed.
fileSystem.root.then("a/b/c/file.txt").put(content)
```

`flow()` is the streaming form of `list()`.

### File URLs

```kotlin
val signedUrl = file.signedUrl                  // signed for the file system's signedUrlDuration
val shortLived = file.signUrl(5.minutes)        // signed for the given duration
val uploadUrl = file.uploadUrl(timeout = 1.hours)
```

With signing turned off (`signedUrlDuration=null`), `signedUrl` is the plain URL.

## Configuration

### Local File System

```kotlin
val settings = ExternalFileSystem.Settings(
    url = "file:///path/to/directory?serveUrl=files&signedUrlDuration=PT1H"
)
```

**Parameters:**

- `serveUrl` (required) - Base URL where files will be served from
    - Relative: `serveUrl=files` → `${context.publicUrl}/files/`
    - Absolute: `serveUrl=https://cdn.example.com/files`
- `signedUrlDuration` (optional) - How long signed URLs remain valid
    - Default: 1 hour
    - Format: `kotlin.time.Duration` text (e.g., `PT1H`, `30m`) or seconds (e.g., `3600`)
    - `forever` or `null` disables signing

The default `Settings()` is `file://<working directory>/local/files?serveUrl=files`.

**Example URLs:**

```
file:///var/storage?serveUrl=files
file:///tmp/uploads?serveUrl=https://example.com/files&signedUrlDuration=PT2H
file:///data?serveUrl=files&signedUrlDuration=forever
```

### S3 File System

For S3 storage, use the `files-s3` module; see [Files-S3 Module](files-s3-module.md).

## File Scanning and Validation

File scanners validate uploaded files:

```kotlin
val scanner = CheckMimeFileScanner(name = "mime-checker", context = settingContext)

try {
    scanner.scan(uploadedFile) // an ExternalFile
} catch (e: FileScanException) {
    println("File validation failed: ${e.message}")
}

// Several scanners in parallel; throws if any fails.
listOf(scanner, clamAv).scan(uploadedFile)
```

`FileScanner.Settings("mime://")` builds a `CheckMimeFileScanner`; `files-clamav` (`clamav://`) and `files-tika`
(`tika://`) provide others.

### CheckMimeFileScanner

It reads the file's first 16 bytes (a ranged read, so large files aren't downloaded) and checks them against the
magic numbers for its claimed media type.  It knows most image, video and audio types and some application types
(ZIP, GZIP, PDF, Office XML, XML); a media type it has no signature for passes.  A file too short to hold the
signature fails cleanly.

## Helper Functions

### Random Filenames

```kotlin
val file = root.thenRandom("upload", "jpg")
// upload-550e8400-e29b-41d4-a716-446655440000.jpg
```

The separator is `-` because `_` is the escape character in `ExternalPath`'s string form.

### Downloading Files

```kotlin
val localFile: KFile? = file.downloadToTemporaryFile() // null if the file doesn't exist
val intoFile: KFile? = file.download(someKFile)
val tempFile: KFile = typedData.download()
```

### Persisting References

```kotlin
val serverFile: ServerFile = file.serverFile // ServerFile("sf://<file system name>/<path>")
val restored: ExternalFile = ExternalFile.Parser(listOf(fileSystem)).parse(serverFile.location)
```

Store the canonical `sf://` form, never a signed URL (it expires).  It is keyed by the file system's service name,
so it keeps resolving if the backend changes under the same name.  `ExternalFile.Parser` also resolves absolute URLs
stored before the canonical form existed, through each file system's `parseLegacyUrl`.  Neither form is signed, so the
parser is for values from your own storage only; client input goes through `parseExternalUrl`.

`ExternalServerFileSerializer` (JVM) does this translation for serialized `ServerFile`s: stored references go out as
signed URLs, and signed URLs coming in are verified and stored as references.

## Advanced Topics

### Paths

`ExternalPath` segments are literal: `then("a/b")` splits on `/`, but a segment's text is otherwise kept exactly.
`toString()` writes the conservative form, escaping every character outside `a-z A-Z 0-9 - .` as `_` plus four hex
digits (and the first dot of an all-dots segment, so `.` and `..` never appear), and
`ExternalPath.fromConservativeString` reads it back.  So any file name, special characters included, survives URLs
and stored references.  The local backend refuses segments it would read as navigation (`.`, `..`, separators).

### Signed URLs (local)

The local file system signs URLs with HMAC-SHA256:

```kotlin
val signedUrl = file.signedUrl
// http://localhost:8080/files/photo.jpg?expires=1234567890000&signature=abc...

val fileObject: ExternalFile? = fileSystem.parseExternalUrl(signedUrl) // null if not under serveUrl
```

- The signing key is generated on first use and kept in `.signingKey` in the root directory; protect it.
- `expires` is in epoch milliseconds; `parseExternalUrl` rejects an expired or tampered URL with
  `IllegalArgumentException`.
- Upload URLs carry `upload=true` inside the signature, so a read URL can't be used to upload and an upload URL
  isn't accepted as a read reference.

`referencesAreUnforgeable` says whether `parseExternalUrl` can tell a URL it issued from one a client made up; it is
false when signing is off.  Code whose security depends on clients only naming files they were given must check it.

### Content Type Storage

The local file system keeps each file's content type in a sidecar under a separate `.metadata` tree that mirrors the
file's path (`.metadata/uploads/photo.jpg.contenttype`).  The sidecars are kept apart from the files so no writable
file name is ever a sidecar name: side by side, an upload of `photo.jpg.contenttype` could choose the type
`photo.jpg` is served with.  Paths under `.metadata` or `.signingKey` can't be read, written or listed.

### Health Checks

`healthCheck()` writes `health-check/test-file-<uuid>.txt`, reads it back, compares type and content, and deletes it.

```kotlin
val health = fileSystem.healthCheck()
when (health.level) {
    HealthStatus.Level.OK -> println("File system is healthy")
    else -> println("Issue: ${health.additionalMessage}")
}
```

## Common Patterns

### Safe File Uploads

```kotlin
suspend fun handleUpload(data: TypedData): ExternalFile {
    val file = fileSystem.root.thenRandom("uploads", data.mediaType.extension)
    file.put(data)
    try {
        scanner.scan(file)
    } catch (e: FileScanException) {
        file.delete()
        throw e
    }
    return file
}
```

### File Copying

```kotlin
source.copyTo(destination) // works across file systems; S3 copies server-side within a bucket
source.moveTo(destination) // copy, then delete the source
```

## Error Handling

```kotlin
try {
    fileSystem.parseExternalUrl(untrustedUrl)
} catch (e: IllegalArgumentException) {
    // Invalid signature or expired URL
}

try {
    source.copyTo(destination)
} catch (e: IllegalArgumentException) {
    // Source doesn't exist
}
```

## Best Practices

1. **Scan uploaded files** - Use a `FileScanner` before serving user uploads
2. **Give clients signed URLs** and verify the ones they send back with `parseExternalUrl`
3. **Persist `serverFile`**, never a signed URL
4. **Set appropriate expiration times** - Balance security vs. usability
5. **Use random filenames** - Prevent collisions and enumeration
6. **Clean up temp files** - `download` and `downloadToTemporaryFile` leave the file for you to delete
7. **Close `get()` and `getRange()` results** - a network backend hands back a live response body

## Limitations and Gotchas

1. **`moveTo()` is not atomic** - If copy succeeds but delete fails, the file exists in both locations
2. **`copyTo()` and `moveTo()` throw on a missing source** - `IllegalArgumentException`
3. **List order is unspecified**
4. **`lastModified` is always null on the local file system**

## Related Modules

- **`files-s3`** - S3 file system implementation ([docs](files-s3-module.md))
- **`files-azure-blob`** - Azure Blob Storage implementation: `azure-blob://<account>:<accountKey>@<container>?signedUrlDuration=1h`, or `azure-blob://<account>@<container>` to authenticate with `DefaultAzureCredential` (managed identity; needs "Storage Blob Data Contributor"; signed URLs capped at 5 days). Optional `endpoint=` for Azurite or sovereign clouds; local tests run against `scripts/azurite.sh`.
- **`files-client`** - `ServerFile`, the serializable file reference shared with clients
- **`files-test`** - Shared test suite for file system implementations
- **`files-clamav`** - ClamAV antivirus scanner
- **`files-tika`** - Apache Tika content-type scanner

## API Reference

See the KDoc in the source files:

- `ExternalFileSystem.kt` - The file system interface and its settings
- `ExternalFile.kt` - File references and `ExternalFile.Parser`
- `ExternalPath.kt` - Paths and their conservative string form
- `KotlinxIoExternalFileSystem.kt` - The local implementation
- `FileScanner.kt` - File validation
- `helpers.kt` - Extension functions
