# KotlinWireFormat: an evolvable binary format (spec)

Status: approved and implemented (`KotlinWireFormat.kt`, `OldKotlinBytesFormat` in `KotlinBytesFormat.kt`).

## Problem

`KotlinBytesFormat` is positional: required fields carry no marker, optional fields an index byte, the end marker appears
only sometimes, and nothing has a length.  A reader can't find the end of a value it doesn't understand, so:

- adding a field anywhere but the end of the top-level object breaks old data (the uncommitted heuristic only helps
  when the parent's next byte happens to be small);
- old readers can't read new data at all, so rolling deploys break in one direction;
- removing, reordering, or retyping a field silently misreads.

It still holds data people rely on (tokens in clients, WebSocket rows in DynamoDB), so it is frozen, not changed.

## Decisions

1. Freeze the current class as `OldKotlinBytesFormat` (same file, same wire format, KDoc says "read existing data
   only").  Add `@Deprecated("Positional format that can't evolve; use KotlinWireFormat for new data. Keep this only to
   read data already written.", ReplaceWith("OldKotlinBytesFormat")) public typealias KotlinBytesFormat =
   OldKotlinBytesFormat`.  Constructor calls through the typealias keep compiling.  Its decoder keeps only the
   end-of-input fix (input ending where an optional field's marker is expected ends the object); the uncommitted
   peek-marker heuristic is reverted because `PartialSerializer` writes markers in insertion order.
2. New `KotlinWireFormat(serializersModule): BinaryFormat` in the same module (`kotlin-bytes-format`, package
   `com.lightningkite.services.serializers`).  Dependencies stay `kotlinx-io` + `kotlinx-serialization-core`; same
   targets (JVM, Android, JS, iOS, macOS).
3. Not kotlinx-serialization-protobuf: it is experimental, conflates null with absent (so `Temperature? = 0.celsius`
   can't round-trip null), rejects null list elements and nullable map values, needs wrapper messages for nested lists,
   and forces annotations everywhere.  We borrow protobuf's *wire ideas* (keyed fields, wire types, length-delimited
   payloads, skip-unknown) and keep kotlinx's *data model* (names, null as a value, any map key, polymorphism by serial
   name).

## Pains seen in Blackstone that this must avoid

From `blackstone/shared/.../models.kt`, `bluetooth/.../protobuf.kt`, `server/.../Server.kt`:

| Pain with kotlinx protobuf | Workaround Blackstone used | KotlinWireFormat |
|---|---|---|
| Nullable field with non-null default: null and absent are the same on the wire | `TemperatureShortSerializer` with a fake `isNullable` descriptor and sentinel `1` meaning N/A | NULL wire type; absent = default, NULL = null |
| `Partial<T>` with `field assign null` can't be expressed | `ProtobufDecoderTest.testEncodeNull` probing descriptors | same NULL wire type; fields in any order |
| Optional-vs-default unrepresentable; schema generator comments defaults | strings defaulted to `""` instead of `null` (`wifiSsid: String = ""`) | natural `String? = null` |
| Must hand-number every field | `@ProtoNumber(n)` on every property | names by default; `@WireId` only to save bytes |
| Signedness per field | `@ProtoType(ProtoIntegerType.SIGNED)` | all signed ints zigzag varint |
| Default serializers for Uuid/Instant/Duration are strings, big | `ProtoBufOverrides` contextual module | unchanged: still the module's choice (not this format's job) |
| Nested lists, nullable elements, nullable map values unsupported | avoided in models | supported |
| Polymorphic as artificial message | avoided | type name + value, same model as JSON |

Blackstone's BLE protocol is real protobuf shared with firmware; it keeps kotlinx protobuf.  This format is for
Kotlin-to-Kotlin data.

## Goals / non-goals

Goals:
- Add or remove fields with defaults anywhere, reorder fields, and rename with `@SerialName`, with old and new readers
  both working (rolling deploys in both directions).
- Kotlin semantics: null is a value distinct from absent; nullable list elements and map values; any map key type;
  sealed and open polymorphism; value classes transparent; `object`s; contextual serializers; custom serializers that
  emit fields out of order (`Partial`) or only one field (`MySealedClassSerializer`).
- Size comparable to the old format (see example), smaller for numeric data (varints).
- Fail fast: wrong wire type, truncated input, trailing bytes, duplicate fields, unknown enum entry or subtype all throw
  `SerializationException`.  Safe on hostile input: lengths are checked against remaining input before allocating.

Non-goals:
- Interop with protobuf or other languages; a schema/IDL.
- Preserving unknown fields across decode/re-encode (no place for them in a Kotlin object).
- Changing nullability or type of *list elements, map keys/values, or top-level values* compatibly (see rules).
- Reading `OldKotlinBytesFormat` data: callers choose the format (Migration).

## Wire format

All multi-byte integers are LEB128 varints unless noted.  Signed `Byte/Short/Int/Long` are zigzag varints.  `Char` is
an unsigned varint of its code.  `Boolean` is varint 0/1 (anything else throws).  `Float`/`Double` are 4/8 bytes
little-endian.

### Wire types

| wt | Name | Payload | Produced by |
|---|---|---|---|
| 0 | VARINT | varint | Boolean, Byte, Short, Int, Long, Char |
| 1 | FIXED64 | 8 bytes | Double |
| 2 | LEN | varint length, then that many bytes | String, enum, class/object, list, map, polymorphic |
| 3 | NULL | nothing | a null value |
| 5 | FIXED32 | 4 bytes | Float |

The wire type is chosen by the first encode call a value makes (`encodeInt`, `encodeString`, `beginStructure`,
`encodeNull`, ...), not predicted from the descriptor, so contextual serializers, value classes (`encodeInline`) and
custom serializers need no special cases.

### Two positions

- **Field position** (an element of a `CLASS`/`OBJECT`): `key` then the value's payload.  `key = (fieldId shl 3) or
  wt`.  Null is `key` with wt NULL.  Non-null values write no null mark.
- **Element position** (top level, list elements, map keys/values, polymorphic value): no key and no wire type; the
  reader knows the type.  Nullable values (descriptor `isNullable`) are preceded by a mark byte `00` null / `01`
  present; non-nullable ones have no mark.  LEN values still carry their length.

### Field ids

- Default: from the element's serial name (`@SerialName` or property name), so ids survive reordering and insertion:
  `fieldId = (fnv1a32(utf8(name)) and 0x7F_FFFF) or 0x80_0000`.  Range [2^23, 2^24): a 24-bit id whose top bit is
  always set, so the key is always a 4-byte varint and hashed ids never equal small explicit ones.
- Optional `@SerialInfo @Target(PROPERTY) annotation class WireId(val id: Int)` with `1 <= id < 2^23`: an explicit
  small id (1-byte key for ids 1..15, 2 bytes to 2047).  Read with `descriptor.getElementAnnotations(i)`, which works on
  every platform and survives `Partial` (it copies element annotations).  Adding or removing `@WireId` changes the id,
  so it is a rename on the wire: choose it when the field is created.
- When a descriptor's field table is built (per `beginStructure`; cache later if measured), two elements with the same
  id throw `SerializationException("X.a and X.b share wire id; add @SerialName or @WireId")`.  Unknown-field collisions
  (a newer writer's field hashing to an old reader's known field) need a 23-bit collision among one class's fields
  and then a matching wire type (about one in 8 million per pair); accepted.

### Values

- String: LEN, UTF-8.  No 64 KB limit.
- Enum: LEN, the entry's serial name.  Not ordinal: entries get inserted mid-list in practice (`UserRole` is ordered
  `NoOne < User < Admin < Developer < Root`; a new role lands in the middle), which silently remaps every ordinal.
  Names cost a few bytes and match JSON.  Unknown name throws.
- Class / object: LEN; payload is its fields in field position, written in whatever order the serializer emits them
  (generated serializers: declaration order).  Decoder accepts any order, skips unknown keys by wire type (VARINT read
  one, FIXED32/64 skip 4/8, LEN skip length, NULL nothing), throws on a duplicate key.  `object` is `00` (empty).
- Defaults: never encoded (`shouldEncodeElementDefault` = false); absent means "the reader's default".  Use kotlinx's
  `@EncodeDefault` on a field whose default might change.  Missing required fields throw kotlinx's
  `MissingFieldException`.  A nullable field without a default is required: the writer always writes it (NULL if null).
- List / Set / Array: LEN; payload is the elements in element position, no count (decoder reads until the payload
  ends; `decodeCollectionSize` returns -1).  A `Byte` in element position is one raw byte (a field's `Byte` is a
  zigzag varint, since it needs a wire type), so `ByteArray` costs length + bytes.
- Map: LEN; payload is key, value, key, value... in element position.  Any key type, nullable keys/values via marks.
- Polymorphic (`POLYMORPHIC`, `SEALED`): LEN; payload is the type's serial name (varint length + UTF-8) then the value
  in element position.  Unknown type name follows kotlinx (module default deserializer, else throw).
- Value class: transparent; the field takes the inner value's wire type.
- `kotlin.uuid.Uuid` (default serializer, serial name `kotlin.uuid.Uuid`): LEN, 16 raw bytes (big-endian, as
  `Uuid.toByteArray`).  Any other length throws.  Instead of the 36-character string.
- `kotlin.time.Instant` (default serializer, serial name `kotlin.time.Instant`; `kotlinx.datetime.Instant` is a
  typealias of it since kotlinx-datetime 0.7): LEN; payload is zigzag varint `epochSeconds` then varint
  `nanosecondsOfSecond`.  Lossless over the whole `Instant` range with nanosecond precision (a single 64-bit varint
  can't hold both: about 55 bits of seconds and 30 of nanoseconds).  Today with nanoseconds is 9 bytes of payload
  instead of a ~30-character ISO string.  Nanoseconds outside 0..999999999, seconds outside `Instant`'s range, or
  leftover payload throw.  LEN (not two VARINT fields) so an old reader skips it like any other field.
- Both are recognized by the serial name of the serializer actually used, wherever it is used (field, element, map
  key or value, nullable, inside a value class, `@Contextual` resolving to the default).  An app serializer under
  another serial name (`InstantAsBsonDateTime`, a string `Uuid` serializer) is written as that serializer says.
- `kotlin.time.Duration` stays an ISO string: typical durations (`PT5M`, `PT1H`) are already 4-8 bytes, so a compact
  form saves 1-4 bytes, and a lossless one has to reproduce `Duration`'s millisecond precision past +-146 years and
  `INFINITE`.  Not worth the rule.  (`database-shared` has `DurationMsSerializer`, already a varint here.)

Every LEN payload must be consumed exactly by its decoder; leftovers or overruns throw.

### Top level

No header.  The format is chosen outside the bytes (token prefix, media type, storage attribute name), so every byte
goes to the value.  `encodeToByteArray` writes the value in element position, except that the top-level structure
(class, object, list, map, polymorphic) has no length: the input's end is its end.  Other top-level LEN values
(strings, enums, `Uuid`, `Instant`) keep theirs.  `decodeFromByteArray` throws unless the value consumes the input
exactly; every length is checked against the remaining input before anything is allocated.

Without that length, input cut between two of the top-level class's fields reads as those fields being absent
(defaults), where a length would have caught it.  Nested values still have lengths.  Every LS caller's bytes are
signed, encrypted, or stored whole, so truncation is caught (or impossible) outside the format.

### Example

```kotlin
@Serializable data class Pet(
    val name: String,
    @WireId(2) val age: Int = 0,
    val nick: String? = "Buddy",
    val tags: List<String?> = emptyList(),
)
Pet(name = "Rex", age = 3, nick = null, tags = listOf("a", null))
```

```
                            Pet, top level: no length
B2 DE B7 2E                 key name (fnv1a "name" = 8D39BDE6 -> id B9BDE6), wt LEN
03 52 65 78                 "Rex"
10                          key age (@WireId 2), wt VARINT
06                          zigzag(3)
F3 B6 B5 2C                 key nick (id B1AB6E), wt NULL: explicitly null, not default
82 BA DD 25                 key tags (id 96EBA0), wt LEN
04  01 01 61  00            4 bytes: present "a", null
```

23 bytes.  The old format writes 22 (`00 03 52 65 78 | 01 00 00 00 03 | 02 00 | 03 00 00 00 02 01 00 01 61 00`).
With `@WireId` on all four fields: 14 (`0A 03 52 65 78 10 06 1B 22 04 01 01 61 00`).  Nested in a list or another
class, `Pet` gains its length byte `17`.  A reader whose `Pet` has no `tags` sees key `82 BA DD 25`, finds no such
field, reads wt LEN, skips `04` + 4 bytes.  A reader with an extra `color: String = "brown"` gets no key for it and
uses the default.

## Evolution rules

"Old" = schema before the change.  Directions: new reader on old data / old reader on new data.

| Change | New reads old | Old reads new |
|---|---|---|
| Add field with default, anywhere | yes (default) | yes (skipped) |
| Add required field | no: give it a default | yes (skipped) |
| Remove field that has a default in old readers | yes (skipped) | yes (default) |
| Remove required field | yes | no: first deploy it with a default, then remove |
| Reorder fields | yes | yes |
| Rename, keeping `@SerialName("old")` | yes | yes |
| Rename without `@SerialName`, or add/remove `@WireId` | = remove + add | = remove + add |
| Change a default | old data omitted the old default and now reads as the new one | likewise | 
| Field `T` -> `T?` | yes | yes while the value is non-null; null throws |
| Field `T?` -> `T` | throws on stored nulls | yes |
| `Byte`/`Short`/`Int`/`Long` widen | yes | yes; out-of-range values throw |
| `Int` <-> value class over `Int`; `List` <-> `Set` | yes | yes |
| `Float` <-> `Double`, `Int` <-> `String`, any other type change | throws (wire type or payload mismatch) | throws |
| Nullability or type of list elements, map keys/values, polymorphic value, top level | no (no wire types there) | no |
| Add enum entry / sealed or polymorphic subtype | yes | yes unless it receives the new value, which throws |
| Remove enum entry / subtype | throws on stored uses | yes |
| Reorder enum entries | yes | yes |
| Move/rename polymorphic class without `@SerialName` | no (serial name is the FQN) | no |
| Read-modify-write by an old reader | n/a | drops the new fields (rewrites defaults) |

## Caller migration

Searched service-abstractions, lightning-server, lightning-server-kiteui, kiteui, lightning-bot, ls-kiteui-starter.
Only lightning-server uses the format (`core` has `api(sa.kotlin.bytes.format)`); the others reach it only through
LS.  No other Service Abstractions module depends on it.

All callers go through `Serialization.kotlinBytesFormat` (`core/.../serialization/Serialization.kt`).  Add
`open val kotlinWireFormat = KotlinWireFormat(serializersModule)`; retype the old property to `OldKotlinBytesFormat`
and keep it for reading.

| Caller (lightning-server) | Data | Lifetime / who reads | Read-side choice |
|---|---|---|---|
| `sessions/.../PublicTinyTokenFormat` | `Authentication`, signed, in client tokens | `expiration` (default 5 min, configurable); any server version | prefix `tw/` (old `tt/`) |
| `sessions/.../PrivateTinyTokenFormat` | `Authentication`, encrypted | same | prefix `w/` |
| `sessions/.../JwtTokenFormat` | `Authentication.cache` bytes in a claim | token lifetime | new claim `wireCache`; the old claim reads as old |
| `core/.../AnonType` (`value`, lazy encode) | type-erased blobs: WS state, task input, WS publish data | depends on container, below | the container records the format (below) |
| `core/.../data/SerializableCache` | `Expiring<T>` blobs inside `Authentication.cache`, `WebSocketConnectRequest.cache`, `Execution.context` | as long as the container (tokens, DynamoDB rows, task payloads) | follows its container: new token prefix or row attribute means new format |
| `engine-aws-serverless/.../AwsWebSocketDynamoDb` | `WebSocketConnectRequest` and state bytes in DynamoDB rows | socket lifetime (hours); read by every version during a deploy | new attributes `wsWireRequest` / `wsWireState`; old attributes read as old |
| `engine-aws-serverless/.../AwsAdapterWs` (`encoding`, publish) | handler storage state (persisted, as above); `WebSocketPublish.data` | publish: async Lambda invoke pinned to `AWS_LAMBDA_FUNCTION_VERSION`, so same code reads it | state: `wsWireState`; publish: just switch |
| `engine-aws-serverless/.../AwsAdapterTask` | `TaskInvoke.input` | async invoke pinned to the same function version (retries up to 6 h, same version) | just switch |
| `core/.../websockets/QueryParamWebSocketHandler`, `MultiplexWebSocketHandler` (state) | handler state via `AnonType` | DynamoDB rows on AWS, memory elsewhere | via `AnonType` |
| `MultiplexWebSocketHandler.messageFromClient` (binary frames, `externalSerialization`) | client-sent `MultiplexMessage` | external clients; none found in the searched repos | switch to the new format |
| `core/.../registerBasicMediaTypeCoders` (`application/x-lightningserver-kotlin-bytes`) | HTTP bodies | external clients | new media type `application/x-lightningserver-kotlin-wire`; the old one stays registered for reading |
| `auth/.../Authentication.kt` "Keep last" comment on `audience` | | | delete once no server writes old tokens or rows |
| Tests: `ReleasedTokenCompatibilityTest`, `LegacySocketRowFixture`, `AwsAdapterWsLegacyStateRowTest`, `WebSocketConnectRequestTest`, `AnonTypeTest`, `SerializationTest` | legacy fixtures | | keep on `OldKotlinBytesFormat`; add new-format counterparts |

The bytes carry no format marker, so every persisted or transmitted blob must have its format recorded beside it.

Rollout: one LS release (implemented in lightning-server) writes the new format immediately and reads both, choosing
by the prefix, claim, attribute or media type above.  During a rolling deploy an old server can't read what a new
one wrote, so tokens and sockets handed between them fail briefly; accepted.  `OldKotlinBytesFormat` stays only to
read old data wherever it can still exist (unexpired tokens, open sockets' rows), and is removed a release or two
later.

## Test plan

In `kotlin-bytes-format/src/commonTest`, run on JVM, JS, Android host, macOS (iOS simulator isn't runnable in the
sandbox; ask the user).

1. Golden bytes: the `Pet` example above (hashed and all-`@WireId`) and one fixture per kind (each primitive at min/max/0, NaN, -0.0, empty and
   non-ASCII strings including surrogate pairs, enum, object, nested class, `List<List<Int>>`, `List<String?>`,
   `Map<Pet, Int?>`, `Map<String?, String>`, `ByteArray`, sealed, open polymorphic via module, value class, nullable
   value class, contextual).  Hex literals in the test; any wire change fails a test.
2. Round trip of the same corpus plus `Partial<T>` with parts inserted out of declaration order and assigned null,
   `Condition`/`Modification` (`MySealedClassSerializer`), `Uuid`, `Instant` (golden bytes too, `DISTANT_PAST`/`FUTURE`,
   nanosecond precision, in lists, maps, nullable, value classes, contextual).
3. Evolution: for each table row, `V1`/`V2` classes sharing a `@SerialName`; encode with one, decode with the other,
   assert the value or the exception.
4. Cross-version fixtures: hex captured from the first released `KotlinWireFormat` and from the released
   `OldKotlinBytesFormat` (existing tests' data), decoded by current code forever.  Never regenerated; new versions add
   fixtures.
5. Strictness: trailing bytes, truncated payload, length past end, 11-byte
   varint, `Boolean` 2, `Long` into `Int` out of range, duplicate key, NULL for a non-null field, wrong wire type,
   unknown enum name, unknown subtype, colliding field ids (two `@WireId(1)`).
6. Fuzz: random and mutated golden inputs decode or throw `SerializationException`, never another exception or a huge
   allocation.
7. lightning-server: tokens with old prefix still read, new prefix round-trips; `LegacySocketRowFixture` rows decode via
   their old attributes; old-bytes `AnonType` and `SerializableCache` entries decode.
8. Size report (printed, with loose upper bounds): `Authentication`, `WebSocketConnectRequest`, `Expiring<User>` in
   both formats.

## Found while researching (resolved)

The uncommitted heuristic in `KotlinBytesFormat.decodeElementIndex` assumed markers come in increasing order.
`PartialSerializer.serialize` writes parts in `parts` map (insertion) order, so an old-format `Partial` whose parts
were inserted out of order ended early and left its marker for the parent.  Reverted; only the end-of-input fix stays.

## Decided

1. Names: `KotlinWireFormat` and `@WireId`.
2. Field ids: 24-bit hash of the serial name by default (4-byte keys), optional `@WireId` for 1-2 byte keys; duplicate
   ids within one class throw.
3. Defaults always omitted; `@EncodeDefault` opts in.  No flag.
4. No header bytes; the format is chosen externally.

5. Binary multiplex frames and HTTP bodies: none found in other clients, so they switch to the new format; the old
   media type stays registered for reading.
6. `PrivateTinyTokenFormat` uses prefix `w/`, `PublicTinyTokenFormat` `tw/`, JWT the claim `wireCache`.
7. One LS release that writes the new format immediately; brief rolling-deploy failures accepted.
8. `OldKotlinBytesFormat` is kept only for reading old data where it can exist, and removed in a release or two.
