# File System Abstraction

The `files` module provides an abstraction for working with file storage systems across different backends (local filesystem, S3, etc.) using a consistent API.

## Overview

The file system abstraction consists of several key interfaces:

- **`PublicFileSystem`** - The main service interface for accessing a file system
- **`FileObject`** - Represents a file or directory with operations like read, write, delete
- **`FileInfo`** - Metadata about a file (size, type, last modified)
- **`FileScanner`** - Service for validating and scanning uploaded files

## Quick Start

### Setting Up a File System

```kotlin
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.SettingContext

// Create a local filesystem
val fileSystem = PublicFileSystem.Settings(
    url = "file:///path/to/storage?serveUrl=http://localhost:8080/files"
).invoke("my-files", settingContext)
```

### Basic File Operations

```kotlin
// Get a reference to a file
val file = fileSystem.root.then("uploads/photo.jpg")

// Write a file
val content = TypedData.bytes(imageBytes, MediaType.Image.JPEG)
file.put(content)

// Read a file
val data = file.get() // Returns TypedData or null if not found

// Get file metadata
val info = file.head() // Returns FileInfo or null
println("Size: ${info?.size} bytes, Type: ${info?.type}")

// Delete a file
file.delete()
```

### Working with Directories

```kotlin
// Navigate directories
val uploadsDir = fileSystem.root.then("uploads")
val userDir = uploadsDir.then("user-123")

// List directory contents
val files = userDir.list() // Returns List<FileObject> or null
files?.forEach { file ->
    println("${file.name}: ${file.url}")
}

// Create nested paths (parent directories created automatically)
val deepFile = fileSystem.root.then("a/b/c/file.txt")
deepFile.put(content) // Creates directories a, b, c if needed
```

### File URLs

```kotlin
// Internal URL (for server-side use)
val internalUrl = file.url

// Signed URL (for client access, with expiration)
val signedUrl = file.signedUrl

// Upload URL (for client uploads)
val uploadUrl = file.uploadUrl(timeout = 1.hours)
```

## Configuration

### Local File System

```kotlin
val settings = PublicFileSystem.Settings(
    url = "file:///path/to/directory?serveUrl=files&signedUrlDuration=PT1H"
)
```

**Parameters:**
- `serveUrl` (required) - Base URL where files will be served from
  - Relative: `serveUrl=files` → uses context.publicUrl
  - Absolute: `serveUrl=https://cdn.example.com/files`
- `signedUrlDuration` (optional) - How long signed URLs remain valid
  - Default: 1 hour
  - Format: ISO 8601 duration (e.g., `PT1H`, `PT30M`) or seconds (e.g., `3600`)
  - Special values: `forever`, `null` (disables signing)

**Example URLs:**
```
file:///var/storage?serveUrl=files
file:///tmp/uploads?serveUrl=https://example.com/files&signedUrlDuration=PT2H
file:///data?serveUrl=files&signedUrlDuration=forever
```

### S3 File System

For S3 storage, use the `files-s3` module (see separate documentation).

## File Scanning and Validation

File scanners validate uploaded files to prevent security issues:

```kotlin
// Create a scanner
val scanner = CheckMimeFileScanner(
    name = "mime-checker",
    context = settingContext
)

// Scan a file
try {
    scanner.scan(fileData)
    println("File is valid")
} catch (e: FileScanException) {
    println("File validation failed: ${e.message}")
}

// Copy and scan (deletes destination if scan fails)
scanner.copyAndScan(source = uploadedFile, destination = safeFile)
```

### Supported File Types (CheckMimeFileScanner)

The built-in `CheckMimeFileScanner` validates these formats by checking magic numbers:
- JPEG images (including EXIF)
- GIF images
- TIFF images
- PNG images
- XML-based formats

## Helper Functions

### Random Filenames

```kotlin
// Generate random filename with UUID
val file = root.thenRandom("upload", "jpg")
// Results in: upload_550e8400-e29b-41d4-a716-446655440000.jpg
```

### Downloading Files

```kotlin
// Download to local temp file
val localFile = fileObject.download(file = null)

// Download TypedData to file
val tempFile = typedData.download()
```

### ServerFile Conversion

```kotlin
// Convert FileObject to ServerFile (for serialization)
val serverFile = fileObject.serverFile
```

## Advanced Topics

### Signed URLs

The local file system implementation uses HMAC-SHA256 signatures to secure file URLs:

```kotlin
// URLs are signed with expiration
val signedUrl = file.signedUrl
// Example: http://localhost:8080/files/photo.jpg?expires=1234567890&signature=abc...

// Verify and parse external URLs
val fileObject = fileSystem.parseExternalUrl(signedUrl)
```

**Important Security Notes:**
- The signing key is stored in `.signingKey` in the root directory
- Ensure proper file permissions on the storage directory (600/700)
- Signed URLs include expiration timestamps to prevent replay attacks
- Upload URLs use a separate flag to prevent signed read URLs from being used for uploads

### Content Type Storage

The local filesystem stores content types in sidecar files:
```
photo.jpg           # The actual file
photo.jpg.contenttype  # Contains "image/jpeg"
```

This allows accurate content type tracking independent of file extensions.

### Health Checks

File systems implement health checks that verify:
- Ability to write files
- Ability to read files with correct content
- Ability to delete files

```kotlin
val health = fileSystem.healthCheck()
when (health.level) {
    HealthStatus.Level.OK -> println("File system is healthy")
    HealthStatus.Level.ERROR -> println("Issue: ${health.additionalMessage}")
}
```

## Common Patterns

### Safe File Uploads

```kotlin
// Pattern 1: Direct upload with scanning
fun handleUpload(data: TypedData): FileObject {
    scanner.scan(data) // Throws if invalid
    val file = fileSystem.root.thenRandom("uploads", data.mediaType.extension)
    file.put(data)
    return file
}

// Pattern 2: Jail/quarantine pattern (see ExternalServerFileSerializer)
// Upload to jail → scan → move to production
```

### File Copying

```kotlin
// Copy file between locations
source.copyTo(destination)

// Move file (copy + delete source)
source.moveTo(destination)

// Copy between file systems
val source = fsA.root.then("file.txt")
val dest = fsB.root.then("file.txt")
dest.put(source.get()!!)
```

### Batch Operations

```kotlin
// List and process all files in a directory
userDir.list()?.forEach { file ->
    val info = file.head()
    println("${file.name}: ${info?.size} bytes")
}

// Delete all files in a directory
userDir.list()?.forEach { it.delete() }
```

## Error Handling

```kotlin
try {
    file.put(content)
} catch (e: IOException) {
    // File system I/O error
}

try {
    scanner.scan(data)
} catch (e: FileScanException) {
    // File validation failed
}

try {
    fileSystem.parseExternalUrl(untrustedUrl)
} catch (e: IllegalArgumentException) {
    // Invalid signature, expired URL, or wrong file system
}
```

## Best Practices

1. **Always scan uploaded files** - Use `FileScanner` to validate user uploads
2. **Use signed URLs for clients** - Never expose internal file paths
3. **Set appropriate expiration times** - Balance security vs. usability
4. **Handle missing files gracefully** - Many operations return null for missing files
5. **Use random filenames** - Prevent filename collisions and enumeration attacks
6. **Clean up temp files** - The `download()` functions create temp files that need manual cleanup
7. **Check file existence** - Use `head()` or `get()` to verify files exist before operations
8. **Secure the signing key** - Protect the `.signingKey` file with proper permissions

## Limitations and Gotchas

1. **`moveTo()` is not atomic** - If copy succeeds but delete fails, file exists in both locations
2. **`copyTo()` silently ignores missing source** - Returns without error if source doesn't exist
3. **List order is unspecified** - Directory listings have no guaranteed sort order
4. **Small files break CheckMimeFileScanner** - Files < 16 bytes cause exceptions
5. **lastModified is always null** - The local filesystem implementation doesn't populate this field
6. **Sidecar files create clutter** - `.contenttype` files appear alongside regular files
7. **URL encoding not handled** - Filenames with special characters may not work correctly

## Related Modules

- **`files-s3`** - S3 file system implementation
- **`files-client`** - Client-side file operations and ServerFile
- **`files-test`** - Shared test suite for file system implementations
- **`files-clamav`** - ClamAV antivirus scanner integration

## API Reference

For detailed API documentation, see the KDoc comments in the source files:
- `PublicFileSystem.kt` - Main file system interface
- `FileObject.kt` - File/directory operations
- `FileScanner.kt` - File validation
- `helpers.kt` - Extension functions
