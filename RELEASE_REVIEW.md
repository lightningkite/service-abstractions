# Service Abstractions — 1.3 Release Review

**Date:** 2026-08-04 · **Branch:** `version-1.3-warnings` · **Scope:** 25 module clusters, 7 cross-cutting lenses, 8 adversarial verification passes

**304 findings.** 52 were adjudicated by independent verifiers instructed to refute: **41 confirmed, 11 downgraded, 2 refuted.**

Detail lives in `tmp/review/`:
- `clusters/` — one report per module cluster
- `lenses/` — repo-wide sweeps (secrets, concurrency, lifecycle, tests, docs, build, API surface)
- `verify/` — refutation attempts, with verdicts and reasoning
- `FINDINGS_INDEX.md` — all findings by severity (regenerate with `tmp/review/index.sh`)

---

## The short version

Three things stand between this branch and a defensible 1.3.

**1. The conformance suites are the product.** This library's promise is that you can swap MongoDB for Postgres, or Redis for DynamoDB, via configuration. That promise is only as good as the tests that verify it — and four abstractions have no such tests at all (`email-test` is 6 lines, `pubsub-test` and `notifications-test` are 2 lines each, `SmsTest` is an empty stub). Nearly every confirmed wrong-results bug in this review sits in exactly the gap those suites would cover. Outbound SMS to the entire US and Canada is broken, and no test could have caught it.

**2. The project keeps building the right guard and not wiring it up.** `verifyNoDirectOtel` is attached to `check`; CI never runs `check`. The `-Pexpensive` cost gate is an unenforced naming convention, and two live-billed tests already sit outside it. DynamoDB's conformance suite is `@Ignore`d. Three Postgres conformance tests are overridden with empty bodies and a "TODO: Make it work". The work is largely done; it just isn't connected.

**3. The documentation does not describe this library.** The first code sample in the root README does not compile. Neither do the samples in the flagship database docs, the files docs, or three of the email READMEs. The CHANGELOG omits this release's own breaking rename. For a public release, this is the first thing every user hits.

**Recommendation:** do not ship 1.3 from this branch. The fixes below are mostly small and well-localized; the sequencing matters more than the volume.

---

## Release blockers

### Confirmed by adversarial verification

These survived an independent agent whose explicit job was to refute them.

| # | Finding | Where |
|---|---|---|
| 1 | **Outbound SMS to US/Canada is broken.** `to.toString()` yields the display format `+1 (555) 123-4567`; Twilio requires E.164. `.raw` is the correct accessor. | `sms-twilio/…/TwilioSMS.kt:147` |
| 2 | **Postgres URL passwords are corrupted.** The regex `(?<user>[^:]*)(?<password>[^@]*)@` has no literal `:` between groups, so `myuser:mypass@` captures the password as `":mypass"`, passed raw to `config.password`. No test exercises this path. | `PostgresDatabase.kt:104` |
| 3 | **`SqlDatabase.disconnect()` never clears its collection cache**, so serverless reconnect reuses collections bound to a closed pool. `PostgresDatabase` received this exact fix in 1.0.0; it was never ported. *Found independently by three agents.* | `SqlDatabase.kt:67-74` |
| 4 | **`SetAllElements` mistranslates compound And/Or conditions** on MongoDB — no De Morgan swap, producing an always-true or always-false query. `ListAllElements` is correct and tested. | `database-mongodb/…/bson.kt:76-82` |
| 5 | **`StringContains` builds LIKE patterns from unescaped input** (`escapeChar = null`; verified against Exposed 1.3.1 that no auto-escaping exists). Literal `%`/`_` act as wildcards. | both SQL backends |
| 6 | **`NotInside` diverges on nullable columns** via SQL three-valued logic — rows the in-memory reference includes are dropped. | both SQL backends |
| 7 | **`updateMany` has no row locking** (`findManyInTransaction` lacks `forUpdate`, unlike `updateOne`). READ_COMMITTED does not prevent the lost update. Wider exposure than filed: there is no scalar-only fast path. | `SqlCollection.kt:836` |
| 8 | **Postgres crashes with bare `NotImplementedError`** on any Map per-key modification. Three conformance tests are overridden with empty bodies to hide it. | `ConditionMapping.kt:612-614` |
| 9 | **`ModifyByKey` throws in-memory but silently creates the key on MongoDB.** Postgres/SQL agree with in-memory; Mongo alone diverges. | `bson.kt:335-342` |
| 10 | **`InMemoryTable.groupCount` drops the null-key group** (`.minus(null)`); Mongo and Postgres both include it. | `InMemoryTable.kt:196` |
| 11 | **`InMemoryDatabase.table()` races on first access** — plain `HashMap` + `getOrPut`. `MongoDatabase` uses `ConcurrentHashMap` + `lazy(SYNCHRONIZED)` for the identical problem. | `InMemoryDatabase.kt:29,57-67` |
| 12 | **KSP incremental cache collides deterministically.** `checksum()` is an additive sum of character codes, so a same-line-count field reorder preserves the exact multiset — regeneration is skipped and stale *positional* indices bind to shifted fields. Silently wrong queries, no compile error. | `CommonSymbolProcessor2.kt:128,163` |
| 13 | **IMAP `pull()` marks messages SEEN before the caller consumes them.** No ack/commit mechanism exists; `mail.imap.peek` is never set. Downstream failure loses the remainder of the batch permanently. | `ImapEmailInboundService.kt:244-296` |
| 14 | **Unbounded MIME multipart recursion** in two separate parsers (SES, IMAP) on attacker-controlled content. ~2000–2700 levels fit in SES's 150KB budget; no `catch(Throwable)` anywhere, and a caller's `catch(Exception)` won't catch `StackOverflowError`. | `MimeParser.kt:45-109` |
| 15 | **SMTP TLS silently disabled off ports 465/587.** `ssl.enable = (port == 465)`, `starttls.enable = (port == 587)`, no other path. `docs/email-module.md:398`'s Mailtrap example (port 2525) is exactly this configuration. | `JavaSmtpEmailService.kt:165-167` |
| 16 | **Mailgun ignores the caller's `from` address** on every send; always `noreply@$domain`. Contradicts `EmailService`'s own KDoc. | `MailgunEmailService.kt` |
| 17 | **Mailgun attachments sent with no filename or content-type** (`FormPart` with default `Headers.Empty`; ktor's filename-aware helper exists, unused). | `MailgunEmailService.kt:55-59` |
| 18 | **S3 `flow()` returns siblings sharing a string prefix** — `unixPathOf()` never appends a trailing `/`, so listing `/users/1` also returns `/users/10`, `/users/123`. | `S3ExternalFileSystem.kt:207` |
| 19 | **S3 `put()` buffers the whole object into memory** when size is unknown (`RequestBody.fromBytes`). No multipart support; 5GB cap, non-resumable. | `S3ExternalFileSystem.kt:311` |
| 20 | **Multi-scanner file validation leaks a temp file on every call** — `item.download()` with no cleanup on any path. | `FileScanner.kt:121-132` |
| 21 | **`LocalPubSub.emit()` blocks on a slow subscriber.** `MutableSharedFlow(0)` is `replay=0, extraBuffer=0, onBufferOverflow=SUSPEND`, contradicting the documented fire-and-forget contract and diverging from Redis. This is the default implementation. | `LocalPubSub.kt:25,31` |
| 22 | **`DynamoDbPubSub.collect()` swallows the caller's own downstream exceptions**, mislabels them as decode failures, and polls on forever. | `DynamoDbPubSub.kt:396-406` |
| 23 | **Exception messages and stack traces bypass telemetry sanitization** entirely. | `kotlinify.kt:22` |
| 24 | **Twilio Media Streams WebSocket is unauthenticated.** `authToken` is accepted and documented for signature validation, then never used. | `TwilioAudioStreamAdapter.kt:58-83` |
| 25 | **`sendDtmf()` interpolates caller-supplied digits into TwiML unescaped** — the only one of 14+ interpolation sites in the file that skips `escapeXml`. | `TwilioPhoneCallService.kt:542-544` |

### High-confidence, not yet independently verified

Filed with file:line evidence and concrete failure scenarios, but no refutation pass was run. Treat as strong leads, confirm before acting.

- **Anthropic *and* OpenAI streaming fabricate a successful `Finished` frame** when the connection drops mid-stream. Ollama has the same defect. **Bedrock does not** — it has an explicit guard and a truncation test. Three of four providers cannot distinguish a truncated response from a complete one.
- **`TelemetrySanitization.Strict.sanitizeUrl` splits on the first `@`**, leaking the tail of any password containing `@`. Second independent hole in the sanitization layer.
- **`HealthStatus.Level` ordinal order contradicts its own docs** (`OK, WARNING, URGENT, ERROR`), and three shipped services already aggregate with `maxBy { it.level }` — URGENT is masked by a co-occurring ERROR.
- **`SealableMap.entries`/`.keys`/`.values` bypass `seal()`** entirely; `SealableList.subList()` copies the flag by value.
- **Voiceagent phone bridge hangs forever on provider WebSocket drop**, never ending the call — with no session duration cap anywhere in the cluster. Two billing meters, no upper bound.
- **Embeddings carry no model provenance** — same-dimension vectors from different models compare silently. Not fixable post-release without a data migration.
- **Stripe `getSubscriptions` never paginates**, silently truncating to 10.
- **Speech buffers all audio in memory before any size check**, unbounded on ElevenLabs/Vosk.

### Not code — ship-stoppers anyway

- **Root `README.md` quickstart does not compile.** Nor do `docs/database-module.md`, `docs/database-query-dsl.md`, `docs/files.md`, `docs/files-s3-module.md`, or three email READMEs.
- **`CHANGELOG.md` omits the `PublicFileSystem` → `External` breaking rename**, and its version heading contradicts the README.
- **A real person's PII and SSH public key are committed to the repository** (`test/…/testTerraform.kt`), alongside a literal `changeme` password for a provisioned admin account. Remove before this repo goes public. *Rotate the key regardless — it is already in git history.*
- **`SUGGESTIONS.md` names four client projects by name.** Confidentiality review before publishing.
- **`ai-koog` exposes a private, unofficial fork of Koog via `api()`**, a full major version behind upstream — permanently part of your public ABI once shipped.
- **`cache-dynamodb` ships a network-downloading, process-spawning DynamoDB-Local bootstrapper as public production API**, reachable via an always-registered URL scheme.
- **`./gradlew test` can make live billed API calls.** `BedrockLiveTest` self-skips only via a manual env check; `ai-bedrock-aws-sdk`'s `LocalTest` hardcodes a personal AWS profile.

---

## Root causes

Most of the blocker list reduces to four systemic gaps. Fixing these is cheaper than fixing 25 bugs, and it catches the ones nobody found.

**1. Contracts are under-specified, so implementations diverge.**
`RegexMatches`, `ModifyByKey`, `SetAllElements`, `NotInside`, `groupCount` nulls, cache TTL-on-update, cache atomicity, pub/sub delivery semantics, stream truncation. Every one is an interface that didn't say enough, implementations that each guessed, and no test to notice. **Fix:** specify the contract in KDoc, then encode it as a conformance test. The test is the specification.

**2. The conformance suites don't exist where they're needed most.**
`email-test` (6 lines), `pubsub-test` (2), `notifications-test` (2), `SmsTest` (empty stub). Cassandra never runs the mandatory concurrency suite or six others. DynamoDB's whole suite is `@Ignore`d. Postgres hides three failures behind empty overrides. **Fix:** fill the four empty suites first — they cover the abstractions with the most confirmed divergence per line of test code.

**3. Guards are written but never wired to CI.**
CI runs only `jvmTest` and `test`. It never runs `check`, so `verifyNoDirectOtel` is inert — and `:database` already violates it. No non-JVM target is ever compiled, despite JS/iOS/macOS being advertised. **Fix:** add `check` to CI; add one non-JVM compile job.

**4. The same bug recurs because there's no shared helper.**
Three unclosed-stream leaks (S3, ElevenLabs, OpenAI TTS). Three streaming-truncation bugs (Anthropic, OpenAI, Ollama) where Bedrock already has the guard. Two broken casing implementations (`casing.kt` and a copy inside the KSP processor). Two sanitization holes. **Fix:** lift the correct implementation into shared code rather than patching each site.

---

## Suggested sequencing

**Before 1.3:**
1. Blockers 1–3 above — one-line-ish fixes, largest blast radius (SMS, Postgres auth, serverless reconnect).
2. Remove committed PII/SSH key; rotate. Confidentiality review of `SUGGESTIONS.md`.
3. Fix the README and flagship docs samples; record the breaking rename in the CHANGELOG.
4. Wire `check` into CI; move the two live-billed tests behind a real gate.
5. Remaining verified blockers, grouped by module to limit context switching.
6. Decide the `ai-koog` fork exposure and the `cache-dynamodb` bootstrapper — both are permanent once published.

**Before 1.4:**
- Fill the four empty conformance suites; specify the contracts they encode.
- Lift shared helpers for stream closing and truncation detection.
- Embedding model provenance (needs a migration story — decide now even if implemented later).
- Adopt `binary-compatibility-validator` while the surface is still changeable.

**Backlog:** the ~250 MEDIUM/LOW findings in `FINDINGS_INDEX.md`, including a full `disconnect()` audit table in `lenses/lifecycle.md` covering every `Service` implementation.

---

## What this review did *not* cover

- **No tests were run.** The Gradle baseline was abandoned after a daemon conflict, and re-running `./gradlew test` unattended is unsafe until the two live-billing tests are gated. Every finding is from static reading.
- **Excluded modules** (`database-cassandra`, `database-migration`, `pubsub-mqtt*`) were reviewed only incidentally — they're commented out of `settings.gradle.kts`. The Cassandra conformance gap surfaced via the tests lens anyway.
- **The 11 unverified BLOCKERs** listed above have not faced a refutation pass. Given that verification downgraded ~21% of what it examined, expect a similar correction rate there.
- **No fixes were applied.** Every agent was report-only.
