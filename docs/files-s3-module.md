# Files-S3 Module

The `files-s3` module provides `S3ExternalFileSystem`, an AWS S3 implementation of `ExternalFileSystem` (formerly
`PublicFileSystem`, still available as a deprecated alias), with signed URLs, server-side copies and Terraform
generation.  It is JVM-only, since it is built on the AWS SDK for Java.

Files are addressed as `ExternalFile`s (`fileSystem.root.then("uploads/photo.jpg")`), a `(fileSystem, ExternalPath)`
pair whose operations delegate to the file system.  See [Files Module](files.md) for the shared API.

## Setup

### Registering the `s3://` scheme

The `s3` scheme is registered by `S3ExternalFileSystem`'s companion object, so the class must be initialized before
`ExternalFileSystem.Settings` parses an `s3://` URL.  Calling one of the `s3(...)` builders below does that; otherwise
reference the class once at startup:

```kotlin
S3ExternalFileSystem // registers s3://
```

### Settings URL

```
s3://<bucket>.s3-<region>.amazonaws.com/                      default credential chain
s3://<profile>@<bucket>.s3-<region>.amazonaws.com/            named profile from ~/.aws/credentials
s3://<accessKey>:<secretKey>@<bucket>.s3-<region>.amazonaws.com/  static credentials
```

The `s3-` before the region is optional.  The only query parameter is `signedUrlDuration`:

| Value | Meaning |
|---|---|
| absent | 1 hour |
| `forever` or `null` | URLs are not signed; the bucket must allow public reads |
| digits, e.g. `3600` | seconds |
| anything else | parsed by `kotlin.time.Duration.parse`, e.g. `1h`, `24h`, `PT30M` |

```kotlin
@Serializable
data class ServerSettings(
    val files: ExternalFileSystem.Settings = ExternalFileSystem.Settings(
        "s3://my-bucket.s3-us-west-2.amazonaws.com/?signedUrlDuration=1h"
    )
)

val fileSystem: ExternalFileSystem = settings.files("files", context)
```

### Builders

```kotlin
import com.lightningkite.services.files.s3.S3ExternalFileSystem.Companion.s3
import software.amazon.awssdk.regions.Region

ExternalFileSystem.Settings.s3(region = Region.US_WEST_2, bucket = "my-app-files")
ExternalFileSystem.Settings.s3(profile = "production", region = Region.EU_WEST_1, bucket = "prod-files")
ExternalFileSystem.Settings.s3(user = accessKeyId, password = secretKey, region = Region.US_WEST_2, bucket = "my-app-files")
```

These produce URLs without `signedUrlDuration`, so their URLs are signed for 1 hour.

### Constructing directly

```kotlin
S3ExternalFileSystem(
    name = "files",
    region = Region.US_WEST_2,
    credentialProvider = DefaultCredentialsProvider.builder().build(),
    bucket = "my-app-files",
    signedUrlDuration = 1.hours,   // null: unsigned URLs
    context = context,
    client = httpClient,           // optional; used for health checks and verifying URLs signed under other keys
)
```

### Credentials

With no user or profile in the URL, the AWS SDK's `DefaultCredentialsProvider` is used: environment variables
(`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`), system properties, web identity tokens, the default profile, and the
container or instance role, in the SDK's order.  Prefer roles in production; static credentials in a settings URL
end up wherever the settings are stored.

## File operations

```kotlin
val file = fileSystem.root.then("uploads/photo.jpg")

file.put(TypedData.bytes(imageBytes, MediaType.Image.JPEG))
val downloaded: TypedData? = file.get()           // null if missing; close it when done
val firstKb: TypedData? = file.getRange(0L..1023L) // inclusive bounds, clamped to the file; a ranged GET to S3
val info: FileInfo? = file.head()                 // type, size, lastModified
file.delete()

// Objects directly under uploads/ (not "subdirectories"), following continuation tokens.
val children: List<ExternalFile> = fileSystem.root.then("uploads").list()
```

`copyTo` between two `S3ExternalFileSystem`s on the same bucket is a server-side `CopyObject`; any other destination
downloads and re-uploads.  `moveTo` is copy then delete, so it is not atomic.

## URLs

```kotlin
val download: String = file.signedUrl               // signed for signedUrlDuration
val shortLived: String = file.signUrl(5.minutes)    // signed for the given duration
val upload: String = file.uploadUrl(15.minutes)     // a signed PUT URL; send a Content-Type header with the upload
```

Object keys are percent-encoded in these URLs.  `signUrl` and `uploadUrl` use a hand-written AWS Signature V4
signer, faster than the SDK's `S3Presigner` when generating many URLs; with `signedUrlDuration` null, `signedUrl` is
the unsigned object URL and `uploadUrl` falls back to the SDK presigner.

Never persist a signed URL: it expires.  Persist `file.serverFile`, the canonical `sf://<name>/<path>` form, and
resolve stored values with `ExternalFile.Parser(listOf(fileSystem)).parse(stored)`.  Absolute S3 URLs stored before
the canonical form existed still resolve through `parseLegacyUrl`, which doesn't check signatures and so must only
see values from your own storage.

### Verifying URLs from clients

```kotlin
val file: ExternalFile? = try {
    fileSystem.parseExternalUrl(urlFromClient) // null if the URL isn't in this bucket
} catch (e: IllegalArgumentException) {
    null // tampered, unsigned, malformed or expired
}
```

A URL signed under the current access key is checked locally, with no network call: a tampered, unsigned or expired
one is rejected.  A URL signed under another access key (temporary credentials that have since rotated, or another
server instance's session) is checked by asking S3 for its first byte, since only S3 holds that key.

With `signedUrlDuration` null nothing is checked: any URL under the bucket is accepted, and
`referencesAreUnforgeable` is false.  Code that relies on clients only naming files they were given must check
`referencesAreUnforgeable`.

## Terraform

```kotlin
import com.lightningkite.services.files.s3.awsS3Bucket

context(emitter: TerraformEmitterAws)
fun infrastructure() {
    TerraformNeed<ExternalFileSystem.Settings>("files").awsS3Bucket(
        signedUrlDuration = 1.hours,         // null: a public-read bucket with unsigned URLs
        forceDestroy = false,                // default true; keep false in production
        corsOrigins = setOf("https://app.example.com"), // default "*"
        kmsKey = null,                       // null: the emitter's deployment-wide encryption key
        versioning = true,
        noncurrentVersionExpiration = 90.days, // requires versioning
    )
}
```

It fills in the setting with the bucket's `s3://` URL and emits:

- an `aws_s3_bucket` named from the project prefix and setting name;
- for a private bucket (`signedUrlDuration` set), a public access block that blocks everything and default
  encryption (SSE-KMS with the resolved key, or AES256), with SSE-C refused;
- for a public bucket (`signedUrlDuration` null), an open public access block and a bucket policy allowing
  `s3:GetObject` on every object to anyone;
- CORS rules allowing `PUT`/`POST` (exposing `ETag`) and `GET`/`HEAD` from `corsOrigins`;
- versioning and a lifecycle rule expiring noncurrent versions, when asked;
- policy statements for the app's role: `s3:ListBucket` and `s3:GetBucketLocation` on the bucket, `s3:GetObject`,
  `s3:PutObject` and `s3:DeleteObject` on its objects.

It throws if `S3ExternalFileSystem` hasn't registered the `s3` scheme.

## Health check

`healthCheck()` writes, reads back, compares and deletes `health-check/test-file-<uuid>.txt`, and fetches it with an
unsigned URL: that fetch succeeding on a signed bucket is a `WARNING` (the bucket is publicly readable), and failing on
an unsigned one is an `ERROR`.

## Testing

`S3ExternalFileSystemTest` runs the shared `FileSystemTests` suite against a real bucket whose settings URL is in
`local/s3.txt`; without it, or when S3 isn't reachable with those credentials, the suite is skipped.  The signature,
URL round-trip and listing tests run offline: signing needs only credentials, and the injectable `client` lets
`S3SignatureVerificationTest` use a Ktor `MockEngine`.

```bash
./gradlew :files-s3:test
```

For application tests that don't need S3, use a local `file://` file system (see [Files Module](files.md)).

## Troubleshooting

- **`Invalid S3 URL`**: the settings URL doesn't match `s3://[credentials@]<bucket>.[s3-]<region>.amazonaws.com/[?params]`.
- **`No handler s3 for ...`**: `S3ExternalFileSystem` was never initialized; see
  [Registering the `s3://` scheme](#registering-the-s3-scheme).
- **`Could not verify signature`** from `parseExternalUrl`: the URL was altered, or was signed by credentials S3
  doesn't accept.  `Signed URL has expired` means it is past `X-Amz-Date` plus `X-Amz-Expires`; check clock sync.
- **Browser upload fails with CORS**: the page's origin isn't in `corsOrigins`, or the upload doesn't send the
  Content-Type header.

## See Also

- [Files Module](files.md) - `ExternalFileSystem`, `ExternalFile` and `ExternalPath`
- [Settings URL Reference](settings-url-reference.md)
- [AWS SDK for Java](https://docs.aws.amazon.com/sdk-for-java/)
