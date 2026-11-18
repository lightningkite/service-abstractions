# Data types

Small value types used across the services and safe to share with clients: validated strings (email, phone), units, coordinates, media types, file contents, money.

## Modules

| Module | Add it for |
|---|---|
| `data-shared` | `EmailAddress`, `PhoneNumber`, trimmed/caseless strings, units (`Length`, `DataSize`, ...), `GeoCoordinate`, `MediaType`, `UuidV7`, `HealthStatus`, model annotations (`@Queryable`, `@Index`, `@Description`, ...).  Multiplatform, client-safe. |
| `data` | `Data` and `TypedData`: file or message content as bytes, text or a stream.  Multiplatform. |
| `kfile` | `KFile`: a small multiplatform file API over kotlinx-io. |
| `currency` | `Cents` (alias `Dollars`): money as a whole number of cents. |
| `kotlin-bytes-format` | `KotlinBytesFormat`, a compact kotlinx.serialization binary format. |

## Validated strings

```kotlin
val email: EmailAddress = " Ada@Example.com ".toEmailAddress()   // "ada@example.com"; throws if invalid
val maybe: EmailAddress? = input.toEmailAddressOrNull()
val phone: PhoneNumber = "(555) 123-4567".toPhoneNumber()        // "+15551234567"; 10 digits get +1
val name: TrimmedString = "  Ada  ".trimmed()
val tag: CaselessString = "Kotlin".caseless()
val handle: TrimmedCaselessString = " @Ada ".trimmedCaseless()

email.raw    // the String inside
```

They serialize as plain strings.  Decoding rejects un-normalized input (`" Ada@Example.com "` throws `SerializationException`), so normalize on the client with these functions before sending.  The constructors are deprecated and throw on un-normalized values.  In database queries, `.raw` reaches the string: `User.email.raw contains "@example.com"`.

## Units

```kotlin
val distance: Length = 5.0.miles          // stored as meters
val upload: DataSize = 25.megabytes       // stored as bytes
distance.kilometers
upload > 10.megabytes
```

`Length`, `Area`, `Volume`, `Mass`, `Speed`, `Acceleration`, `Force`, `Pressure`, `Energy`, `Power`, `Temperature`, `DataSize` each store one SI value (`meters`, `bytes`, ...), compare, and have extension builders in their companion (`import com.lightningkite.services.data.Length.Companion.miles`).  `megabytes` means mebibytes (1024²).

## Other types

- `GeoCoordinate(latitude, longitude)`, with GeoJSON and array serializers; used by `distanceBetween` queries and [geocoding](geocoding.md).
- `MediaType`: `MediaType.Image.PNG`, `MediaType.Application.Json`, `MediaType.fromExtension("pdf")`, `type.extension`.
- `UuidV7.generate()`: time-ordered UUIDs (sort by creation), `.raw` for the `Uuid`.
- `ZonedDateTime`, `OffsetDateTime`: kotlinx-datetime values with their zone, ISO-8601 serialized.
- `HealthStatus(level, checkedAt, additionalMessage)`, returned by every service's `healthCheck()`.

## File contents: `Data` and `TypedData`

```kotlin
val text = TypedData.text("hello", MediaType.Text.Plain)
val bytes = TypedData.bytes(pngBytes, MediaType.Image.PNG)
val streamed = TypedData.source(source, MediaType.Video.MP4, size = length)

val s: String = text.text()
val b: ByteArray = bytes.data.bytes()
```

`Data` is `Bytes`, `Text`, `Source`, `Sink`, `SuspendingSource` or `SuspendingSink`.  Stream-backed data can be read only once: read it a second time and it throws `IllegalStateException`.  `TypedData` is `AutoCloseable`; close stream-backed values you don't read.  `write(to)` doesn't close the sink you pass.

## Files on disk: `KFile`

```kotlin
val dir = KFile("local/exports").also { it.mkdirs() }
val file = dir.then("report.csv")
file.writeString("id,total\n1,10\n")
val contents: String? = file.readStringOrNull()
workingDirectory.then("local", "files")
```

## Money: `Cents`

```kotlin
val price: Cents = 19.99.dollars           // Cents(1999)
val total = price * 3 + 250.cents
total.toString()                           // "$62.47"
Cents.parseOrNull("62.47")
```

Arithmetic stays in whole cents (`*` and `/` by fractions round).  Serialized as the number of cents.

## Gotchas

- **`EmailAddress.EMPTY` / `PhoneNumber.EMPTY`** are the only un-normalized values allowed, meaning "not provided".  `"".toPhoneNumber()` still throws.
- **Old serializers are gone**: use `EmailAddress.serializer()` etc. instead of `EmailAddressSerializer`.
