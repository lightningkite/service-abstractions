# Files

`ExternalFileSystem` stores files that clients download by URL: the local disk, S3, or Azure Blob Storage.  An `ExternalFile` is a file system plus an `ExternalPath`; reads and writes use `TypedData` (bytes plus a `MediaType`, see [data.md](data.md)).  `FileScanner` checks uploads before you trust them.

## Modules

| Module | Add it for |
|---|---|
| `files` | `ExternalFileSystem`, `ExternalFile`, local `file://`, the `mime://` scanner.  Multiplatform. |
| `files-client` | `ServerFile`, the client-safe reference type for models.  Multiplatform. |
| `files-s3` | `s3://`.  JVM. |
| `files-azure-blob` | `azure-blob://`.  JVM. |
| `files-tika` | `tika://`: checks content against its media type with Apache Tika.  JVM. |
| `files-clamav` | `clamav://`: virus scanning with clamd, plus a Terraform helper for a ClamAV host.  JVM. |
| `files-test` | `FileSystemTests`, the shared contract suite. |

## Quick start

```kotlin
@Serializable
data class AppSettings(
    val files: ExternalFileSystem.Settings = ExternalFileSystem.Settings(),  // ./local/files
)

suspend fun main() {
    S3ExternalFileSystem  // registers s3://
    val settings = Json.decodeFromString<AppSettings>("""{"files": "s3://my-bucket.s3-us-west-2.amazonaws.com"}""")
    val files = settings.files("files", TestSettingContext())

    val avatar = files.root.then("avatars", "ada.png")
    avatar.put(TypedData.bytes(pngBytes, MediaType.Image.PNG))
    val url: String = avatar.signedUrl          // hand this to a client
    val back: TypedData? = avatar.get()          // null if missing
}
```

## Common tasks

### Browse and read

```kotlin
val dir = files.root.then("reports/2026")       // "a/b" and ("a", "b") are the same path
val children: List<ExternalFile> = dir.list()
val info: FileInfo? = dir.then("q3.pdf").head()  // type, size, lastModified; null if missing
val text: String? = dir.then("notes.txt").get()?.text()
val firstKb: TypedData? = dir.then("q3.pdf").getRange(0L..1023L)
```

### Let a client upload directly

```kotlin
val upload = files.root.thenRandom("upload", "jpg")        // upload-<uuid>.jpg
val putUrl: String = upload.uploadUrl(timeout = 15.minutes) // client PUTs the bytes here
```

### Copy, move, delete

```kotlin
upload.copyTo(files.root.then("archive", upload.name))   // server-side copy within S3
upload.moveTo(files.root.then("processed", upload.name))
upload.delete()
```

### Store a reference in a model

Persist `ServerFile`, never a URL: signed URLs expire, and backend URLs break when you change backends.

```kotlin
@Queryable
data class Profile(override val _id: Uuid, val avatar: ServerFile? = null) : HasId<Uuid>

val stored: ServerFile = upload.serverFile                  // "sf://files/upload-....jpg"
val file: ExternalFile = ExternalFile.Parser(listOf(files)).parse(stored.location)
```

- The canonical form is `sf://<service name>/<path>`.  It survives moving from one backend to another as long as the service keeps its name and the files are copied across.
- `ExternalFile.Parser` trusts what it's given: use it only on values your server stored.  For a URL from a client, use `files.parseExternalUrl(url)`, which checks the signature.  If `files.referencesAreUnforgeable` is false (no `signedUrlDuration`), a client can name any file under the root.
- `ServerFile` serializes through a contextual serializer: on a server (Lightning Server registers one) it is stored as `sf://` and sent to clients as a signed URL.

### Scan uploads

```kotlin
val scanner = FileScanner.Settings("tika://")("scanner", context)
scanner.scan(upload)                    // throws FileScanException on a mismatch or a virus
listOf(tika, clamav).scan(upload)       // all of them
```

`mime://` checks magic numbers for common image, audio, video, archive and document types; `tika://` knows over a thousand types and how they relate (docx is a zip); `clamav://` scans for viruses.  `tika://` rejects types it doesn't recognize unless `unknownTypes=allow` (or `allowedUnknownTypes=a/b,c/d`).

## Settings URLs

| Scheme | Setting | Module | Example |
|---|---|---|---|
| `file` | `ExternalFileSystem.Settings` | `files` | `file:///var/uploads?serveUrl=files` |
| `s3` | `ExternalFileSystem.Settings` | `files-s3` | `s3://my-bucket.s3-us-west-2.amazonaws.com?signedUrlDuration=PT1H` |
| `azure-blob` | `ExternalFileSystem.Settings` | `files-azure-blob` | `azure-blob://myaccount@uploads` |
| `mime` | `FileScanner.Settings` | `files` | `mime://` |
| `tika` | `FileScanner.Settings` | `files-tika` | `tika://?unknownTypes=block` |
| `clamav` | `FileScanner.Settings` | `files-clamav` | `clamav://localhost:3310/UNIX` |

Parameters and quirks: [Settings URL reference: Files](settings-url-reference.md#files-externalfilesystemsettings).  Reference `S3ExternalFileSystem`, `AzureBlobExternalFileSystem`, `TikaFileScanner` or `ClamAvFileScanner` before parsing settings.

- `signedUrlDuration` (all three file systems): absent = 1 hour; `forever` or `null` = unsigned public URLs; digits = seconds; otherwise ISO-8601 (`PT30M`).
- `file://` requires `serveUrl`, the base URL the files are served from.  A relative value is appended to the context's `publicUrl`.  Serving those URLs is your server's job (Lightning Server does it).
- `s3://` takes `accessKey:secretKey@` or `profile@` before the bucket; without either it uses the default AWS credential chain.  The host must be `<bucket>.s3-<region>.amazonaws.com` or `<bucket>.<region>.amazonaws.com`; custom endpoints (MinIO, LocalStack) aren't supported.
- `azure-blob://` without an account key uses `DefaultAzureCredential`; `endpoint=` points at Azurite.

## Testing

`ExternalFileSystem.Settings()` (local files under `./local/files`) works in tests; use a temporary directory to keep runs apart:

```kotlin
val files = ExternalFileSystem.Settings("file://${tempDir}?serveUrl=files")("files", TestSettingContext())
```

To check an implementation against the shared contract, subclass `FileSystemTests` and override `system`.

## Gotchas

- **Unsigned means public**: with `signedUrlDuration=forever`, anyone with the URL can read the file, and the bucket must allow public reads.
- **Path segments**: `ExternalPath` rejects `.`, `..` and separators inside a segment.
- **Health checks write** `health-check/test-file.txt`.
- **Buckets with dots** in their names don't parse.
