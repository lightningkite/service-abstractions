# Files-S3 Module

The `files-s3` module provides an AWS S3 implementation of the `PublicFileSystem` interface, enabling file storage in S3 buckets with support for signed URLs, CORS configuration, and Terraform infrastructure generation.

## Overview

This module is part of the service-abstractions library's file storage system. It implements the `PublicFileSystem` interface using AWS S3 as the backend storage, with features including:

- **Signed URLs**: Custom AWS Signature V4 implementation for faster URL generation
- **Server-side operations**: Optimized copy operations within the same bucket
- **CORS support**: Pre-configured CORS rules for web browser uploads
- **Terraform integration**: Automatic infrastructure provisioning
- **Connection pooling**: Efficient HTTP client reuse via AwsConnections
- **Credential management**: Support for multiple authentication methods

## Quick Start

### Basic Setup

```kotlin
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.SettingContext

// Using default credentials (environment variables, instance profile, etc.)
val fileSystem = PublicFileSystem.Settings
    .s3(
        region = Region.US_WEST_2,
        bucket = "my-app-files"
    )
    .invoke("files", context)

// Using static credentials
val fileSystem = PublicFileSystem.Settings
    .s3(
        user = "AKIAIOSFODNN7EXAMPLE",
        password = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        region = Region.US_WEST_2,
        bucket = "my-app-files"
    )
    .invoke("files", context)

// Using named AWS profile
val fileSystem = PublicFileSystem.Settings
    .s3(
        profile = "production",
        region = Region.EU_WEST_1,
        bucket = "prod-files"
    )
    .invoke("files", context)
```

### Configuration URL Format

The module supports URL-based configuration:

```kotlin
// Default credentials
PublicFileSystem.Settings("s3://my-bucket.s3-us-west-2.amazonaws.com/")

// Named profile
PublicFileSystem.Settings("s3://myprofile@my-bucket.s3-us-west-2.amazonaws.com/")

// Static credentials
PublicFileSystem.Settings("s3://user:password@my-bucket.s3-us-west-2.amazonaws.com/")

// With signed URL duration
PublicFileSystem.Settings("s3://my-bucket.s3-us-west-2.amazonaws.com/?signedUrlDuration=1h")

// Public bucket (no signed URLs)
PublicFileSystem.Settings("s3://my-bucket.s3-us-west-2.amazonaws.com/?signedUrlDuration=null")
```

### Serializable Configuration

```kotlin
@Serializable
data class ServerSettings(
    val files: PublicFileSystem.Settings = PublicFileSystem.Settings(
        "s3://my-bucket.s3-us-west-2.amazonaws.com/?signedUrlDuration=24h"
    )
)

val settings = Json.decodeFromString<ServerSettings>(configJson)
val fileSystem = settings.files("files", context)
```

## Core Features

### File Operations

```kotlin
// Get a file reference
val file = fileSystem.root.then("uploads/photo.jpg")

// Upload a file
val content = TypedData(
    data = Data.Bytes(imageBytes),
    mediaType = MediaType.Image.JPEG
)
file.put(content)

// Download a file
val downloaded = file.get()
if (downloaded != null) {
    val bytes = downloaded.data.bytes()
    val mediaType = downloaded.mediaType
}

// Check if file exists
val info = file.head()
if (info != null) {
    println("Size: ${info.size} bytes")
    println("Type: ${info.type}")
    println("Modified: ${info.lastModified}")
}

// Delete a file
file.delete()

// List directory contents
val directory = fileSystem.root.then("uploads/")
val files = directory.list()
files?.forEach { file ->
    println(file.name)
}
```

### Signed URLs

Signed URLs provide time-limited access to files without requiring AWS credentials on the client side:

```kotlin
// Get a signed URL for downloading (GET request)
val downloadUrl = file.signedUrl
// URL is valid for the duration specified in signedUrlDuration
// Example: https://my-bucket.s3.us-west-2.amazonaws.com/uploads/photo.jpg?X-Amz-Algorithm=...

// Get a signed URL for uploading (PUT request)
val uploadUrl = file.uploadUrl(timeout = 15.minutes)
// Client can PUT to this URL directly

// Use in API responses
@Serializable
data class FileResponse(
    val url: String,
    val expiresIn: Duration
)

fun getFileUrl(path: String): FileResponse {
    val file = fileSystem.root.then(path)
    return FileResponse(
        url = file.signedUrl,
        expiresIn = 1.hours
    )
}
```

#### Public vs. Signed URLs

```kotlin
// Public bucket (signedUrlDuration = null)
// URLs work without signatures
val publicFs = PublicFileSystem.Settings(
    "s3://public-bucket.s3-us-west-2.amazonaws.com/?signedUrlDuration=null"
).invoke("public", context)
val file = publicFs.root.then("image.jpg")
println(file.url) // https://public-bucket.s3.us-west-2.amazonaws.com/image.jpg
// Anyone can access this URL

// Private bucket (signedUrlDuration = 1.hours)
val privateFs = PublicFileSystem.Settings(
    "s3://private-bucket.s3-us-west-2.amazonaws.com/?signedUrlDuration=1h"
).invoke("private", context)
val secureFile = privateFs.root.then("sensitive.pdf")
println(secureFile.signedUrl)
// https://private-bucket.s3.us-west-2.amazonaws.com/sensitive.pdf?X-Amz-Algorithm=...&X-Amz-Signature=...
// Only valid for 1 hour
```

### Performance Optimization

The module includes a custom AWS Signature V4 implementation that is significantly faster than the AWS SDK's built-in presigner:

```kotlin
// Custom signing (used automatically)
val customSignedUrl = file.signedUrl  // ~10x faster

// AWS SDK signing (available for comparison)
val officialSignedUrl = (file as S3FileObject).signedUrlOfficial

// Performance test results show custom implementation is 5-10x faster
// for generating thousands of signed URLs
```

### Server-Side Copy

When copying files within the same bucket, the module uses S3's server-side copy operation, which is much faster than downloading and re-uploading:

```kotlin
val source = fileSystem.root.then("original.jpg")
val destination = fileSystem.root.then("copy.jpg")

// This triggers a server-side copy (no data transfer)
source.copyTo(destination)

// Moving a file (copy + delete)
source.moveTo(destination)
```

### URL Parsing

Parse S3 URLs back into file objects:

```kotlin
// Parse internal URL (unsigned)
val internalUrl = "https://my-bucket.s3.us-west-2.amazonaws.com/path/to/file.txt"
val file = fileSystem.parseInternalUrl(internalUrl)

// Parse external URL (signed, with validation)
val externalUrl = "https://my-bucket.s3.us-west-2.amazonaws.com/path/to/file.txt?X-Amz-Algorithm=..."
try {
    val secureFile = fileSystem.parseExternalUrl(externalUrl)
    // Signature is valid
} catch (e: IllegalArgumentException) {
    // Signature is invalid or URL has expired
}
```

## Terraform Integration

The module provides Terraform configuration generation for automated infrastructure provisioning:

```kotlin
import com.lightningkite.services.terraform.TerraformEmitterAws

context(emitter: TerraformEmitterAws)
fun configureInfrastructure() {
    // Create a public bucket
    need<PublicFileSystem.Settings>("publicFiles").awsS3Bucket(
        signedUrlDuration = null,  // Public access
        forceDestroy = true
    )

    // Create a private bucket with signed URLs
    need<PublicFileSystem.Settings>("privateFiles").awsS3Bucket(
        signedUrlDuration = 1.hours,
        forceDestroy = false  // Prevent accidental deletion in production
    )
}
```

### Generated Resources

The Terraform function creates:

1. **S3 Bucket** with project-prefixed name
2. **CORS Configuration** allowing browser uploads
3. **IAM Policy** granting S3 access to Lambda execution role
4. **Public Access Configuration** (only if signedUrlDuration is null):
   - Public access block disabled
   - Bucket policy allowing `s3:GetObject` for all principals

### Terraform Output

```hcl
resource "aws_s3_bucket" "publicFiles" {
  bucket_prefix = "myproject-publicfiles"
  force_destroy = true
}

resource "aws_s3_bucket_cors_configuration" "files" {
  bucket = aws_s3_bucket.publicFiles.bucket

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["PUT", "POST"]
    allowed_origins = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET", "HEAD"]
    allowed_origins = ["*"]
  }
}

resource "aws_s3_bucket_public_access_block" "publicFiles" {
  bucket = aws_s3_bucket.publicFiles.id

  block_public_acls       = false
  block_public_policy     = false
  ignore_public_acls      = false
  restrict_public_buckets = false
}

resource "aws_s3_bucket_policy" "publicFiles" {
  depends_on = [aws_s3_bucket_public_access_block.publicFiles]
  bucket     = aws_s3_bucket.publicFiles.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "PublicReadGetObject"
      Effect    = "Allow"
      Principal = "*"
      Action    = ["s3:GetObject"]
      Resource  = [
        aws_s3_bucket.publicFiles.arn,
        "${aws_s3_bucket.publicFiles.arn}/*"
      ]
    }]
  })
}
```

## Configuration Options

### Signed URL Duration

Controls how long signed URLs remain valid:

```kotlin
// Short-lived URLs (15 minutes)
"?signedUrlDuration=15m"

// Medium-lived URLs (1 hour)
"?signedUrlDuration=1h"

// Long-lived URLs (24 hours)
"?signedUrlDuration=24h"

// Custom duration (ISO 8601)
"?signedUrlDuration=PT30M"

// Numeric seconds
"?signedUrlDuration=3600"

// Public access (no signing)
"?signedUrlDuration=null"
// or
"?signedUrlDuration=forever"
```

### Credential Sources

The module supports multiple AWS credential providers:

#### 1. Environment Variables
```bash
export AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
export AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
export AWS_REGION=us-west-2
```

```kotlin
PublicFileSystem.Settings("s3://my-bucket.s3-us-west-2.amazonaws.com/")
```

#### 2. Named Profile
```bash
# ~/.aws/credentials
[production]
aws_access_key_id = AKIAIOSFODNN7EXAMPLE
aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
```

```kotlin
PublicFileSystem.Settings("s3://production@my-bucket.s3-us-west-2.amazonaws.com/")
```

#### 3. Static Credentials (Not Recommended for Production)
```kotlin
PublicFileSystem.Settings("s3://user:password@my-bucket.s3-us-west-2.amazonaws.com/")
```

#### 4. Instance Profile / ECS Task Role (Automatic)
When running on EC2 or ECS, credentials are automatically obtained from the instance metadata service.

#### 5. Web Identity Token (EKS, Lambda)
Automatically used when running in Kubernetes (EKS) or AWS Lambda with appropriate IAM roles.

## Health Checks

The module implements health checks for monitoring:

```kotlin
val health = fileSystem.healthCheck()
when (health.level) {
    HealthStatus.Level.OK -> println("S3 connection is healthy")
    HealthStatus.Level.WARNING -> println("S3 has issues: ${health.additionalMessage}")
    HealthStatus.Level.ERROR -> println("S3 is down: ${health.additionalMessage}")
}

// Health check performs:
// 1. Write test file
// 2. Read test file
// 3. Verify content matches
// 4. Delete test file
```

## Testing

### Unit Tests

The module includes a comprehensive test suite:

```kotlin
class S3PublicFileSystemTest : FileSystemTests() {
    override val system: S3PublicFileSystem? by lazy {
        PublicFileSystem.Settings(
            "s3://testprofile@test-bucket.us-west-2.amazonaws.com"
        ).invoke("test", TestSettingContext()) as? S3PublicFileSystem
    }
}
```

### Running Tests

Tests require AWS credentials and a real S3 bucket:

```bash
# Configure credentials
aws configure --profile testprofile

# Run tests
./gradlew :files-s3:jvmTest
```

### Mocking for Tests

For tests that don't need real S3:

```kotlin
@Test
fun testWithMock() {
    val mockFileSystem = mockk<S3PublicFileSystem>()
    every { mockFileSystem.root.then(any()).signedUrl } returns
        "https://mock-bucket.s3.amazonaws.com/test.txt?signature=mock"

    // Test your application logic
}
```

## Best Practices

### 1. Use Appropriate Signed URL Duration

```kotlin
// Download URLs: Short duration
val download = file.signedUrl  // Uses system-wide duration

// Upload URLs: Match expected upload time
val upload = file.uploadUrl(timeout = 5.minutes)  // For quick uploads
val largeUpload = file.uploadUrl(timeout = 1.hours)  // For large files
```

### 2. Handle Missing Files

```kotlin
val content = file.get()
if (content == null) {
    // File doesn't exist
    return Result.failure("File not found")
}
```

### 3. Use Path Conventions

```kotlin
// Organize by user/type/date
fun getUserFilePath(userId: String, type: String): FileObject {
    val date = Clock.System.now().toString().take(10)  // YYYY-MM-DD
    return fileSystem.root.then("users/$userId/$type/$date/")
}
```

### 4. Leverage Server-Side Copy

```kotlin
// Efficient: Server-side copy within bucket
source.copyTo(destination)

// Inefficient: Downloads and re-uploads (if to different bucket)
// Still works, just slower
```

### 5. Cache Signed URLs

```kotlin
// Generate once, use multiple times within validity period
val url = file.signedUrl
// Share this URL with clients for the next hour
```

### 6. Production Configuration

```kotlin
@Serializable
data class ProductionConfig(
    val files: PublicFileSystem.Settings = PublicFileSystem.Settings(
        "s3://prod-files.s3-us-east-1.amazonaws.com/?signedUrlDuration=1h"
    )
)

// In production:
// - Use IAM roles (instance profile/ECS task role)
// - Use private buckets with signed URLs
// - Set forceDestroy = false in Terraform
// - Enable versioning and lifecycle policies
// - Configure proper CORS origins (not "*")
```

## Security Considerations

### 1. Credential Management

- **Never hardcode credentials** in source code
- Use IAM roles when running on AWS infrastructure
- Rotate credentials regularly
- Use least-privilege IAM policies

### 2. Bucket Permissions

```kotlin
// Public bucket: Use only for truly public assets
"?signedUrlDuration=null"

// Private bucket: Use for user data, sensitive files
"?signedUrlDuration=1h"
```

### 3. CORS Configuration

The default CORS allows all origins (`*`). For production:

```hcl
# Restrict to your domains
cors_rule {
  allowed_origins = ["https://app.example.com", "https://www.example.com"]
  allowed_methods = ["GET", "HEAD"]
  allowed_headers = ["*"]
}
```

### 4. Signed URL Validation

```kotlin
// External URLs are validated
try {
    val file = fileSystem.parseExternalUrl(userProvidedUrl)
    // Safe to use
} catch (e: IllegalArgumentException) {
    // Reject invalid/expired URL
}
```

## Performance Tips

### 1. Batch Operations

```kotlin
// List files once, process multiple
val files = directory.list() ?: emptyList()
files.forEach { file ->
    // Process each file
}
```

### 2. Concurrent Uploads

```kotlin
suspend fun uploadMultiple(files: List<Pair<String, ByteArray>>) = coroutineScope {
    files.map { (path, bytes) ->
        async {
            val file = fileSystem.root.then(path)
            file.put(TypedData(Data.Bytes(bytes), MediaType.Application.OctetStream))
        }
    }.awaitAll()
}
```

### 3. Pre-signed URL Generation

The custom signing implementation is optimized for generating many URLs quickly:

```kotlin
// Efficient: Generate thousands of URLs
val urls = files.map { it.signedUrl }  // ~10x faster than AWS SDK
```

## Troubleshooting

### Common Issues

#### 1. "No bucket provided" Error
```kotlin
// Wrong: Missing bucket name
"s3://.s3-us-west-2.amazonaws.com/"

// Correct:
"s3://my-bucket.s3-us-west-2.amazonaws.com/"
```

#### 2. "Could not verify signature" Error
- Check that credentials haven't expired
- Verify system clock is synchronized
- Ensure URL hasn't been modified

#### 3. Slow Performance
- Use custom signing (automatic) for signed URLs
- Enable HTTP connection pooling via AwsConnections
- Consider using CloudFront CDN for frequently accessed files

#### 4. CORS Errors in Browser
- Verify CORS configuration allows your domain
- Check that proper headers are set when uploading
- Ensure browser is using the signed upload URL correctly

## API Reference

### S3PublicFileSystem

Main class implementing PublicFileSystem for S3.

**Properties:**
- `name: String` - Service name
- `region: Region` - AWS region
- `bucket: String` - S3 bucket name
- `signedUrlDuration: Duration?` - Signed URL validity duration
- `context: SettingContext` - Setting context
- `s3: S3Client` - Synchronous S3 client
- `s3Async: S3AsyncClient` - Asynchronous S3 client

**Methods:**
- `parseInternalUrl(url: String): S3FileObject?` - Parse internal URL
- `parseExternalUrl(url: String): S3FileObject?` - Parse and validate external URL
- `healthCheck(): HealthStatus` - Check S3 connectivity

### S3FileObject

Represents a file or directory in S3.

**Properties:**
- `system: S3PublicFileSystem` - Parent file system
- `path: File` - File path
- `name: String` - File name
- `parent: FileObject?` - Parent directory
- `url: String` - Unsigned URL
- `signedUrl: String` - Signed URL for GET

**Methods:**
- `then(path: String): S3FileObject` - Resolve relative path
- `list(): List<FileObject>?` - List directory contents
- `head(): FileInfo?` - Get file metadata
- `put(content: TypedData)` - Upload file
- `get(): TypedData?` - Download file
- `copyTo(other: FileObject)` - Copy file
- `delete()` - Delete file
- `uploadUrl(timeout: Duration): String` - Generate signed PUT URL

## Platform Support

- **JVM**: Full support (requires Java 17+)
- **Android**: Not supported (use JVM-based backend)
- **JavaScript**: Not supported
- **Native**: Not supported

The module is JVM-only due to AWS SDK dependencies.

## Dependencies

```kotlin
// AWS SDK
api("software.amazon.awssdk:s3")
api("software.amazon.awssdk:aws-crt-client")

// Core modules
api(project(":basis"))
api(project(":files"))
api(project(":aws-client"))
api(project(":http-client"))

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive")
```

## See Also

- [Files Module](files-module.md) - PublicFileSystem interface
- [Basis Module](basis-module.md) - Core abstractions
- [AWS SDK Documentation](https://docs.aws.amazon.com/sdk-for-java/)
- [S3 Documentation](https://docs.aws.amazon.com/s3/)
