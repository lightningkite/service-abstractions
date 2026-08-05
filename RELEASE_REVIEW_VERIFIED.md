# Service Abstractions 1.3 — Verified Findings

**Date:** 2026-08-04 · **Branch:** `version-1.3-warnings`

Every finding below was adjudicated by an independent agent instructed to **refute** it, not confirm it. Findings that no verifier examined are excluded — see `RELEASE_REVIEW.md` for those, and `tmp/review/FINDINGS_INDEX.md` for all 304.

**52 adjudicated: 41 confirmed · 11 downgraded · 2 refuted.**

Severity shown is **post-verification**. Where it changed, the original is struck through.

## Fix-effort legend

| Rating | Meaning |
|---|---|
| **Mechanical** | One line to a few lines. The correct fix is unambiguous, requires no design decision, and carries essentially no risk of breaking something else. |
| **Localized** | Confined to one class or file. The fix is known and low-risk, but needs a test to pin it. |
| **Needs care** | The fix is understood, but it changes behavior callers may depend on, spans multiple backends, or requires restructuring. |
| **Decide first** | The code can't be fixed until someone decides what the contract *should* be. Both sides are currently defensible; picking one is a product decision, not a coding one. |

---

## Confirmed blockers

| # | Finding | Severity | Location | Fix |
|---|---|---|---|---|
| 1 | **Outbound SMS to US/Canada is broken.** `to.toString()` yields display format `+1 (555) 123-4567`; Twilio requires E.164. `.raw` is correct. | BLOCKER | `sms-twilio/…/TwilioSMS.kt:147` (also telemetry attr `:131`) | **Mechanical** — `to.toString()` → `to.raw` |
| 2 | **Postgres URL passwords corrupted.** Regex `(?<user>[^:]*)(?<password>[^@]*)@` lacks a literal `:`, so the password captures as `":mypass"` and reaches `config.password` raw. | BLOCKER | `database-postgres/…/PostgresDatabase.kt:104` | **Mechanical** — insert `:` between groups, matching the mysql/mariadb pattern |
| 3 | **`SqlDatabase.disconnect()` never clears its collection cache** — serverless reconnect reuses collections bound to a closed pool. `PostgresDatabase` got this fix in 1.0.0; never ported. *Found independently by 3 agents.* | BLOCKER | `database-sql/…/SqlDatabase.kt:67-74` vs `PostgresDatabase.kt:76-85` | **Localized** — port the existing fix; also scope `SqlCollection.prepare` off `GlobalScope` |
| 4 | **`SetAllElements` mistranslates compound And/Or** — no De Morgan swap, yielding an always-true/always-false Mongo query. `ListAllElements` is correct and tested. | BLOCKER | `database-mongodb/…/bson.kt:76-82` | **Localized** — mirror `ListAllElements`: build `Condition.Not(condition)` and `$nor` |
| 5 | **`StringContains`/`RawStringContains` build LIKE patterns from unescaped input** (`escapeChar = null`; verified against Exposed 1.3.1 that no auto-escaping exists). Literal `%`/`_` act as wildcards. | BLOCKER | `database-sql/…/SqlConditionMapping.kt:200-216`; `database-postgres/…/ConditionMapping.kt:264-281` | **Localized** — escape `%`, `_`, and the escape char; pass a real `escapeChar` so SQL emits `ESCAPE '\'` |
| 6 | **`NotInside` diverges on nullable columns** via SQL three-valued logic — `NOT (NULL IN (…))` is NULL, so rows the in-memory reference includes are dropped. | BLOCKER | `database-sql/…/SqlConditionMapping.kt:161-168`; `database-postgres/…/ConditionMapping.kt:220-227` | **Localized** — emit `(col IS NULL) OR NOT (col IN (…))` for nullable columns |
| 7 | **`updateMany` has no row locking.** `findManyInTransaction` lacks `forUpdate`, unlike `updateOne`. READ_COMMITTED doesn't prevent the lost update. Wider than filed — there is no scalar-only fast path. | BLOCKER | `database-sql/…/SqlCollection.kt:629-675`, `:836-856` | **Needs care** — add `forUpdate` to the multi-row select, or restructure to lock-then-update per row |
| 8 | **Postgres crashes with bare `NotImplementedError`** on any Map per-key modification. Three conformance tests are overridden with empty bodies to hide it. | BLOCKER | `database-postgres/…/ConditionMapping.kt:612-614` | **Decide first** — implement via JSONB, or ship a clear `UnsupportedOperationException` and document the gap |
| 9 | **`ModifyByKey` throws in-memory on a missing key but MongoDB silently creates it.** Postgres/SQL agree with in-memory; Mongo alone diverges. | BLOCKER | `database-shared/…/Modification.kt:258-261` vs `database-mongodb/…/bson.kt:335-342` | **Decide first** — pick require-exists or upsert semantics, document it, then align Mongo |
| 10 | **`InMemoryTable.groupCount` drops the null-key group** (`.minus(null)`); Mongo and Postgres both include it. Absent from the adjacent `groupAggregate`, supporting oversight-not-design. | BLOCKER | `database/…/InMemoryTable.kt:196` | **Mechanical** — delete the `.minus(null)` call |
| 11 | **`InMemoryDatabase.table()` races on first access** — plain `HashMap` + `getOrPut`. `MongoDatabase` uses `ConcurrentHashMap` + `lazy(SYNCHRONIZED)` for the identical problem. | BLOCKER | `database/…/InMemoryDatabase.kt:29,56-67` | **Mechanical** — copy the `MongoDatabase` pattern |
| 12 | **KSP incremental cache collides deterministically.** `checksum()` is an additive sum of char codes, so a same-line-count field reorder preserves the multiset exactly — regeneration is skipped and stale *positional* indices bind to shifted fields. Silently wrong queries, no compile error. | BLOCKER | `database-processor/…/CommonSymbolProcessor2.kt:128,163,72-89`; binding at `AnnotationProcessor.kt:225-231` | **Localized** — real content hash (SHA-256) + route commonMain output through `createNewFile(dependencies, …)` |
| 13 | **IMAP `pull()` marks messages SEEN before the caller consumes them.** No ack/commit mechanism exists in `WebhookAdapter.pull()`; `mail.imap.peek` is never set, so JavaMail likely also flags implicitly. Downstream failure loses the batch remainder permanently. | BLOCKER | `email-inbound-imap/…/ImapEmailInboundService.kt:276-289` | **Needs care** — return unmarked and add an ack path; the interface currently has nowhere to put one |
| 14 | **Unbounded MIME multipart recursion** in two separate parsers on attacker-controlled content. ~2000–2700 levels fit SES's 150KB budget; no `catch(Throwable)` anywhere, and `catch(Exception)` won't catch `StackOverflowError`. | BLOCKER | `email-inbound-ses/…/MimeParser.kt:45-109`; `email-inbound-imap/…/ImapEmailInboundService.kt:368-409` | **Localized** — thread a depth limit (20–30) through both and fail fast |
| 15 | **SMTP TLS silently disabled off ports 465/587.** `ssl.enable = (port == 465)`, `starttls.enable = (port == 587)`, no other path. `docs/email-module.md:398`'s Mailtrap example (port 2525) is exactly this. | BLOCKER | `email-javasmtp/…/JavaSmtpEmailService.kt:165-167` | **Needs care** — add explicit `tls`/`starttls` URL params defaulting to required; touches the URL scheme |
| 16 | **Mailgun ignores the caller's `from` address** on every send; always `noreply@$domain`. Contradicts `EmailService`'s own KDoc and the SMTP implementation's correct handling. | BLOCKER | `email-mailgun/…/MailgunEmailService.kt:65` | **Mechanical** — `email.from?.value ?: <default>`, label for display name only |
| 17 | **Mailgun attachments sent with no filename or content-type** — `FormPart` with default `Headers.Empty`; ktor's filename-aware helper exists but is unused. | BLOCKER | `email-mailgun/…/MailgunEmailService.kt:52-59` | **Localized** — build `Content-Disposition`/`Content-Type` headers per part |
| 18 | **S3 `flow()` returns siblings sharing a string prefix.** `unixPathOf()` never appends a trailing `/`, so listing `/users/1` also returns `/users/10`, `/users/123`. | BLOCKER | `files-s3/…/S3ExternalFileSystem.kt:232-260`, `:207` | **Mechanical** — use `"$unixPath/"` as the prefix for non-root paths; match the filter |
| 19 | **S3 `put()` buffers the whole object into memory** when size is unknown (`RequestBody.fromBytes`). | BLOCKER | `files-s3/…/S3ExternalFileSystem.kt:297-317` | **Needs care** — switch to `AsyncRequestBody.fromInputStream`/multipart; changes the upload path |
| 20 | **Multi-scanner file validation leaks a temp file on every call** — `item.download()` with no cleanup on any path. | BLOCKER | `files/…/FileScanner.kt:121-132` | **Mechanical** — `try { … } finally { asFile.delete() }` |
| 21 | **`LocalPubSub.emit()` blocks on a slow subscriber.** `MutableSharedFlow(0)` is `replay=0, extraBuffer=0, onBufferOverflow=SUSPEND` — contradicts the documented fire-and-forget contract and diverges from Redis. This is the default implementation. | BLOCKER | `pubsub/…/LocalPubSub.kt:25,31`; `DebugPubSub` `:60,66` | **Decide first** — bounded buffer + `DROP_OLDEST`, or change the documented contract |
| 22 | **`DynamoDbPubSub.collect()` swallows the caller's own downstream exceptions**, mislabels them as decode failures, and polls on forever. | BLOCKER | `pubsub-aws/…/DynamoDbPubSub.kt:396-406` | **Mechanical** — narrow the `try` to `decode(message)` only |
| 23 | **Exception messages and stack traces bypass telemetry sanitization** entirely — every `telemetryTrace`/span path. | BLOCKER | `otel-jvm/…/kotlinify.kt:21-24,44-46,66-68`; `OtelTelemetryBackend.kt:167-193` | **Localized** — add `sanitizeExceptionMessage`, route both paths through it |

---

## Confirmed high

| # | Finding | Severity | Location | Fix |
|---|---|---|---|---|
| 24 | **Twilio Media Streams WebSocket is unauthenticated.** `authToken` is accepted and documented for signature validation, then never used. | HIGH | `phonecall-twilio/…/TwilioAudioStreamAdapter.kt:58-83` | **Needs care** — implement Twilio WS signature validation from scratch |
| 25 | **`sendDtmf()` interpolates caller-supplied digits into TwiML unescaped** — the only one of 14+ interpolation sites skipping `escapeXml`. | HIGH | `phonecall-twilio/…/TwilioPhoneCallService.kt:542-544` | **Mechanical** — wrap in `escapeXml()`, and/or validate digits |
| 26 | **`TelemetrySanitization.Strict.sanitizeFilePath` returns the full unredacted path** for any directory-style input. | HIGH | `basis/…/TelemetrySanitization.kt:86-111` | **Mechanical** — fall back to a sentinel instead of `path` when the trim is empty |
| 27 | **SMTP header injection — `customHeaders` and attachment filenames.** `InternetHeaders.addHeader` builds `name + ": " + value` with no CRLF validation. ~~BLOCKER~~ — 2 of 4 claimed vectors refuted (see below). | ~~BLOCKER~~ **HIGH** | `email-javasmtp/…/JavaSmtpEmailService.kt:291-293`, `:310,312` | **Mechanical** — reject `\r`/`\n` at construction time, per the codebase's fail-fast style |
| 28 | **`PrefixCache` doesn't override `compareAndSet`**, silently downgrading Redis's atomic Lua CAS to the non-atomic default. (`modify` *is* correctly delegated.) | HIGH | `cache/…/PrefixCache.kt` | **Mechanical** — one-line delegate mirroring `modify()` |
| 29 | **`MapCache`/`MapCacheUnsafe` don't override `compareAndSet`** — the non-atomic default applies even to the flagship `ram` backend. | HIGH | `cache/…/MapCache.kt`, `MapCacheUnsafe.kt` | **Localized** — reuse the `entries.compute` pattern already in `modify()` |
| 30 | **`MemcachedCache.compareAndSet`'s delete branch is an unconditional `delete`**, ignoring the CAS token obtained two lines earlier, and hardcodes success. *Confidence upgraded by verifier.* | HIGH | `cache-memcached/…/MemcachedCache.kt:222-228` | **Localized** — use the CAS-guarded delete overload; propagate real success |
| 31 | **No networked cache backend implements `disconnect()`.** Redis leaks a Netty event loop + TCP connection; Memcached its selector thread/socket pool; DynamoDB its client. | HIGH | `RedisCache.kt`, `MemcachedCache.kt`, `DynamoDbCache.kt` | **Needs care** — add overrides *and* make lazily-built resources rebuildable on reconnect |
| 32 | **`delay://<number>` crashes on every call.** Bare-number branch builds a zero-width range; `Random.nextDouble` requires `from < until`. Deterministic, not probabilistic. | HIGH | `database/…/Database.kt:151-166`; `DelayedTable.kt:22-29` | **Mechanical** — special-case `start == endInclusive` |
| 33 | **`postChange`/`postNewValue` drop the caller's `orderBy`** on the `*IgnoringResult` variants. Sibling `postRawChanges` forwards it correctly — copy-paste omission. | HIGH | `database/…/simpleSignals.kt:93-112`, `:162-181` | **Mechanical** — forward `orderBy` in both overrides |
| 34 | **`interceptDelete` does a non-atomic find-then-delete** — the callback can fire for a row that isn't the one deleted. | HIGH | `database/…/simpleSignals.kt:809-832` | **Needs care** — single `deleteOne` returning the model; changes callback timing |
| 35 | **`FullTextSearch` on a null value matches the literal string `"null"`** instead of short-circuiting false, unlike every other nullable-aware condition. | HIGH | `database-shared/…/Condition.kt:199-229` | **Mechanical** — short-circuit to `false` when `on == null` |
| 36 | **`RegexMatches` is full-string in-memory but substring on every backend.** ~~BLOCKER~~ — no DSL builder exists for this condition on any backend, so it's unreachable from the fluent query surface. | ~~BLOCKER~~ **HIGH** | `database-shared/…/Condition.kt:234-241`; all 3 backend mappings | **Decide first** — pick anchored or unanchored, then align four implementations |
| 37 | **No multipart upload support** — every `put()` is a single `PutObject`; 5GB cap, non-resumable, no `abortMultipartUpload` path. | HIGH | `files-s3/…/S3ExternalFileSystem.kt:297-317` | **Needs care** — new upload path with part management |
| 38 | **`DirectAwsCredentials` public data class prints the raw AWS secret** via generated `toString()`. Verifier notes: real defect, but **latent** — no current call site exercises the leak. | HIGH | `files-s3/…/S3ExternalFileSystem.kt:89-95` | **Mechanical** — override `toString()` to redact, or make the class `internal` |
| 39 | **`CheckMimeFileScanner` throws `EOFException` instead of `FileScanException`** for files under 16 bytes — the existing `bytes.size < 16` branch is unreachable. | HIGH | `files/…/FileScanner.kt:174-184` | **Mechanical** — `readAtMostTo` instead of `readByteArray(16)` |
| 40 | **`ClamAvFileScanner.scan()` has no timeout** and hangs forever on an unresponsive daemon. Verified by decompiling `clamav-client-2.1.2`: plain blocking `SocketChannel` with no `SO_TIMEOUT`. | HIGH | `files-clamav/…/ClamAvFileScanner.kt:130-144` | **Mechanical** — `withTimeout(…)`, treating timeout as fail-closed |
| 41 | **Mailgun silently drops all attachments** while reporting an accurate count — matches the file's own TODO. | HIGH | `email-inbound-mailgun/…/MailgunEmailInboundService.kt:447-455` | **Needs care** — replace the hand-rolled text parser with Jakarta Mail |
| 42 | **No size limit on inbound webhook bodies** (Mailgun/SendGrid); SendGrid's hand-rolled multipart scanner allocates per byte. | HIGH | `MailgunEmailInboundService.kt:179-198,240`; SendGrid equivalent | **Localized** — cap before buffering; parser rewrite is separate |
| 43 | **Mailgun `replyTo` fabricates `unknown@example.com`** when the header is absent; every other provider returns null. ~~BLOCKER~~ — impact is a silent bounce to a reserved non-routable domain, not misdirection. | ~~BLOCKER~~ **HIGH** | `email-inbound-mailgun/…/MailgunEmailInboundService.kt:368` | **Mechanical** — return `null` directly |
| 44 | **`DynamoDbCache.modify`/`compareAndSet` are non-atomic** despite the interface contract. ~~BLOCKER~~ — `add()` in the same file proves the atomic pattern is available. | ~~BLOCKER~~ **HIGH** | `cache-dynamodb/…/DynamoDbCache.kt`; contract at `cache/…/Cache.kt:114-116,147-177` | **Localized** — `ConditionExpression`, mirroring `add()` |
| 45 | **DynamoDB's entire cache conformance suite is `@Ignore`d**, including the regression test for a past lost-update bug. ~~BLOCKER~~ — downgraded since the suite needs infrastructure CI may not have. | ~~BLOCKER~~ **HIGH** | `cache-dynamodb/…/DynamoTest.kt:17` | **Needs care** — root-cause the CI failure or move to Testcontainers |
| 46 | **`MapCache.modify()` preserves an existing TTL** when `timeToLive` is omitted; Redis, Memcached, and DynamoDB all strip it. ~~BLOCKER~~ — undocumented divergence on the default backend. | ~~BLOCKER~~ **HIGH** | `cache/…/MapCache.kt:147` | **Decide first** — pick the semantic, align four backends, add a `CacheTest` case |
| 47 | **`JsonFileDatabase` never overrides `disconnect()`** — the documented shutdown hook is a no-op. ~~BLOCKER~~ — the class's own KDoc says "NOT production-ready", and a JVM shutdown hook already flushes on normal exit. | ~~BLOCKER~~ **HIGH** | `database-jsonfile/…/JsonFileDatabase.kt:106-120` | **Localized** — override `disconnect()` to do what `close()` does |
| 48 | **KSP per-class generation swallows exceptions into a source comment** instead of failing the build; no `logger.error` anywhere in the module. ~~BLOCKER~~ — typical outcome is a confusing late `unresolved reference`, not silently wrong production behavior. | ~~BLOCKER~~ **HIGH** | `database-processor/…/AnnotationProcessor.kt:144-236`, `:102-127` | **Mechanical** — `logger.error(msg, declaration)`; stop swallowing |

---

## Downgraded to medium

| # | Finding | Severity | Location | Fix |
|---|---|---|---|---|
| 49 | **Inbound attachment filenames are never sanitized.** Confirmed unsanitized in all 3 providers — but the verifier grepped every `.filename` usage and found nothing in this library builds a path or storage key from it. | ~~HIGH~~ **MEDIUM** (docs) | SendGrid `:356`, SES `MimeParser.kt:86`, IMAP | **Mechanical** — document `ReceivedAttachment.filename` as attacker-controlled |
| 50 | **MongoDB's non-atomic upsert fallback can create duplicate rows** under concurrency. ~~HIGH~~ — `Table`'s atomicity contract already discloses exactly this. | ~~HIGH~~ **MEDIUM** | `database-mongodb/…/bson.kt:391-422` | **Needs care** — requires a unique index or a rethink of the fallback |

---

## Refuted

| Claim | Verdict |
|---|---|
| **SMTP header injection via `subject`** | **REFUTED** — `MimeMessage.setSubject()` routes through `MimeUtility.fold()`/`makesafe()`, which folds embedded CR/LF into a continuation line of the *same* header. `makesafe()`'s own doc comment states it exists "to prevent header injection errors." Verified against `jakarta.mail-api-2.1.3`/`angus-mail-2.0.5` sources. |
| **SMTP header injection via display names** (from/to/cc/bcc labels) | **REFUTED** — same `fold()`/`makesafe()` protection, reached via `InternetAddress.toString(Address[], used)`. |

---

## Reading the table

**19 of 52 are Mechanical** — findings 1, 2, 10, 11, 16, 18, 20, 22, 25, 26, 27, 28, 32, 33, 35, 38, 39, 40, 43, 48, 49. Several are single-token changes (`to.toString()` → `to.raw`; delete `.minus(null)`; insert a `:`). Between them they cover broken SMS to North America, broken Postgres auth, wrong aggregate results, an S3 listing that returns the wrong objects, a per-request temp-file leak, and an AWS secret in `toString()`.

**5 are Decide first** — 8, 9, 21, 36, 46. These are the contract questions, and they're the review's real finding: `ModifyByKey` semantics, `RegexMatches` anchoring, pub/sub delivery guarantees, cache TTL-on-update, and Postgres Map modifications. Each has multiple defensible answers and no test pinning any of them. Writing the conformance test *is* the fix; the code change follows from it.

**Nothing here requires architectural change.** The heaviest items (7, 13, 15, 19, 24, 31, 37, 41) are contained rewrites of a single method or code path.
