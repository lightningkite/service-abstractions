# Changelog

## [Unreleased - 1.0.0 candidate]

### Breaking Changes
- **cache-dynamodb-kmp**: Module removed. Migrate to `cache-dynamodb` (JVM) or another KMP cache.
- **basis**: `ConcurrentMutableMap` is now an `expect class` (was factory fn). Source-compatible at call sites; **binary-incompatible** — recompile dependents.
- **basis**: `SharedResources` ctor param type changed `MutableMap` → `ConcurrentMutableMap`.
- **data**: `Data.write(to: Sink)` no longer closes the caller's sink (Bytes/Text/Sink variants). Callers must close it themselves.
- **email**: `EmailService.sendBulk` default now runs concurrently (`async`/`awaitAll`); one failure cancels siblings (was sequential, best-effort).
- **files-s3 (JVM)**: `S3FileObject.list()` propagates exceptions instead of returning `null` on error.
- **notifications-fcm**: `send()` chunks 500 tokens in parallel via `Semaphore(8)`; chunk ordering not preserved. Transport-level chunk failures surface as per-token `Failure` instead of throwing.
- **pubsub-aws (DynamoDB)**: `doInitialize()` failure is terminal (no retry on next `ensureReady()`).
- **aws-client**: `AwsConnections.total` default lowered `Int.MAX_VALUE` → `1000`. Health checks may newly flip to DEGRADED/ERROR — tune if needed.
- **sms-inbound-twilio**: Webhook fails closed (`SecurityException`) when `webhookUrl` is null (was a silent skip).
- **voiceagent-openai**: Event channel `UNLIMITED` → `Channel(64, DROP_OLDEST)`; tool-call/audio events may drop under back-pressure.

### Security
- **database-postgres**: Removed `addLogger(StdOutSqlLogger)` and a `println("list is ...")` debug line that leaked all SQL and result rows.
- **email-mailgun**: `verifySignature` uses constant-time `MessageDigest.isEqual` (was case-insensitive `String` equality — timing-attackable).
- **email-inbound-ses**: `verifySnsSignature` hardened — requires `CN=sns.amazonaws.com`, requires URL path matching `^/SimpleNotificationService-[a-z0-9]+\.pem$`, rejects timestamps outside ±1h, cert download wrapped in `withTimeout(10s)` on `Dispatchers.IO`, auto-confirm connect timeout 10s → 5s.
- **sms-inbound-twilio**: Fails closed on missing `webhookUrl` (see Breaking Changes).
- **Telemetry PII reduction**: cache spans (Redis/Memcached/DynamoDB/MapCache) hash keys via `TelemetrySanitization.hashCacheKey` instead of recording plaintext; email-inbound spans (mailgun/sendgrid/ses) redact subject and reduce from/to to domain only; files-s3 spans use `sanitizeFilePathWithDepth` instead of full paths.

### Performance
- **email-javasmtp**: `Transport.send` moved to `Dispatchers.IO` (no longer blocks caller's dispatcher).
- **notifications-fcm**: Parallel chunk dispatch (see Breaking Changes for ordering caveat).
- **files-clamav**: ClamAV client cached and reused; invalidated on scan/connection exception.
- **sms-twilio**: `send()` retries on HTTP 429/5xx with 1s/2s/4s backoff. **Note**: no idempotency key — 5xx-after-accept may duplicate SMS.
- **email-inbound-imap**: Webhook POST retried 3× with 1s/2s/4s backoff.

### Bug Fixes
- **cache-redis**: CAS Lua script — sentinel for "no TTL" changed `nil` → `""`, and `if ARGV[2] then` (always true in Lua) fixed to `if ARGV[2] ~= ''`. Previously CAS-without-TTL called `PSETEX` with a bad arg.
- **database-mongodb**: `MongoTable.findOneAndDelete` is now atomic (was non-atomic find-then-delete; could double-process under contention).
- **database-postgres**: `PostgresDatabase.disconnect()` closes per-collection scopes (was leaking `GlobalScope` async).
- **database-jsonfile**: `handleCollectionDump()` guarded by `ReentrantLock` — fixes race between shutdown hook and `close()`.
- **email-inbound-sendgrid**: `KeyFactory` cached at class level; `Signature` is now `ThreadLocal` (thread-safety bug). Attachments with duplicate filenames are renamed `${i}_${filename}` instead of being silently dropped by `distinctBy`.
- **files-kotlinx-io**: `get()` uses try-open pattern instead of exists-then-open (eliminates TOCTOU race).
- **pubsub-aws (DynamoDB)**: `collect()` filters out the `seq=0` counter row on bootstrap — first subscribers previously missed the backlog's first record.

### Added
- `OpenTelemetrySub.span` helper adopted across cache, files, and email-inbound modules with sanitized span attributes.

### Removed
- `cache-dynamodb-kmp` module (see Breaking Changes).

### Known Issues
- (none currently tracked for 1.0.0)
