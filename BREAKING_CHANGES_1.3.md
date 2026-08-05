# Breaking changes introduced by the 1.3 security/correctness fixes

Each of these is a consequence of fixing a verified defect, not a gratuitous change.
All need a CHANGELOG entry and a migration note before release.

---

## 1. `RedisCache` / `MemcachedCache` constructors now take a client *factory*

**Was:** constructor took an already-built client instance.
**Now:** takes `() -> Client` (`makeLettuceClient`, `makeClient`).

**Why:** fixing the "no networked cache backend implements `disconnect()`" defect
requires genuinely releasing the client's threads and event loop. Lettuce's own docs
state a `RedisClient` "should be discarded after calling shutdown", and XMemcached has
no public restart API — so a working `connect()` after `disconnect()` requires
rebuilding the client, not merely reconnecting it. This matches the pattern
`DynamoDbCache` already used (`makeClient`).

**Migration:** wrap existing construction in a lambda. All 8 in-repo call sites updated.

---

## 2. Twilio Media Streams requires `configureExpectedUrl()`

**Was:** the Media Streams WebSocket endpoint performed no signature validation at all —
the `authToken` parameter was accepted, documented as being for validation, and ignored.
**Now:** validation is mandatory and fails closed whenever `authToken` is non-null.

**Impact:** `TwilioPhoneCallService` always passes a non-null `authSecret` when
constructing `audioStream`, so **every existing consumer must call
`service.audioStream.configureExpectedUrl(theirStreamUrl)` once at startup** or all
Media Streams connections are rejected with `SecurityException`.

**Why fail-closed:** an auth check that silently allows traffic when misconfigured is
the bug being fixed. A grace period would reintroduce the hole for anyone who doesn't
read release notes.

**Migration:** add the one `configureExpectedUrl(...)` call at startup. Passing a null
`authToken` preserves the old no-validation behaviour for anyone deliberately running
behind other authentication.

---

## 3. `interceptDelete` callback now fires *after* the delete, not before

**Was:** a separate `find()` then `deleteOne()`, so under concurrent modification the
`onDelete` callback could fire for a row that was not the one actually deleted.
**Now:** a single delete call, with the callback receiving the model it returned.

**Why:** there is no way to obtain "the row genuinely deleted" atomically while also
firing the callback beforehand. Correctness required the timing change.

**Impact:** no in-repo callers exist. External callers (lightning-server side) that
depend on before-delete timing — e.g. to veto a delete, or to read related state before
it disappears — will observe different behaviour. KDoc updated to state after-delete
semantics.

**Migration:** callers needing pre-delete work should do it explicitly before invoking
the delete, rather than relying on the callback's old timing.

---

## 4. SMTP now requires TLS by default

**Was:** TLS was inferred from the port — `ssl.enable = (port == 465)`,
`starttls.enable = (port == 587)`, and *no TLS at all on any other port*, silently
sending credentials in the clear. The project's own `docs/email-module.md:398` Mailtrap
example (port 2525) demonstrated exactly this.
**Now:** `requireTls` defaults to true on every port; opting out requires an explicit
`?insecure=true` on the `smtp://` URL.

**Impact:** an SMTP server on a non-standard port that does **not** support STARTTLS
will now fail to connect instead of silently downgrading. That is the intended
behaviour. Mailtrap on 2525 supports STARTTLS, so the documented example now works
securely with no user change.

**Migration:** add `?insecure=true` only where plaintext SMTP is genuinely required
(e.g. a local test relay), and treat that as a deliberate, visible choice.

---

## 5. `Modification.ModifyByKey` removed entirely

**Was:** `it.someMap.modifyByKey(mapOf("k" to { it += 1 }))` — apply a nested modification
to an existing map entry.
**Now:** removed. `Combine` (`it.someMap += mapOf(...)`) and `RemoveKeys` are unaffected.

**Why:** no supported database can express it without reading the row first and applying
the change locally. In-memory, JSON-file, SQL, Cassandra, and MongoDB all did exactly
that internally, so it never saved a round trip over doing it yourself. Postgres was the
sole exception — a single atomic UPDATE over the map's parallel arrays.

That exception is what settled it. An operation that is genuinely atomic on one backend
and a read-modify-write race on the other five is worse than not having it: it reads like
a guarantee, callers build on it, and it silently degrades to a race when they switch
backends — the precise failure this library exists to prevent. Better no atomicity than
fake atomicity.

It also had no production users, and its semantics were never agreed on: MongoDB silently
created absent keys, the in-memory reference threw `NoSuchElementException`, and Postgres
threw `UnsupportedOperationException`. Three backends, three answers, no test pinning any
of them.

**Migration:** read, compute, write back.

```kotlin
val current = table.get(id)!!
table.updateOneById(id, modification { it.someMap += mapOf("k" to current.someMap.getValue("k") + 1) })
```

For safety under concurrency, put the value you read into the condition to make it a real
compare-and-swap — stronger than `modifyByKey` provided on five of six backends:

```kotlin
table.updateOne(
    condition = condition { it._id eq id and (it.someMap["k"] eq old) },
    modification = modification { it.someMap += mapOf("k" to old + 1) }
)
```

**Wire compatibility:** `Modification` is `@Serializable`. A peer still sending
`{"ModifyByKey": …}` will now fail to deserialize rather than get a clean error. This
library's `ModifyByKey` had no production consumers, so no deprecation cycle was run —
keeping it deprecated would have meant keeping all five driver implementations alive,
which was the entire cost being removed.

---

## Also worth a release note (not breaking, but behavioural)

- **`RegexMatches` is now unanchored everywhere.** The in-memory evaluator changed from
  full-string `matches` to partial `containsMatchIn`, aligning it with MongoDB
  (`$regex`), Postgres (`~`), and SQL (`REGEXP`), all of which were already unanchored.
  Anyone who relied on the in-memory backend's stricter full-string behaviour should
  anchor their patterns explicitly with `^…$`.
- **`Modification.ModifyByKey` and the `modifyByKey` DSL are removed** — this one *is*
  breaking; see §5 above for the reasoning and migration.
- **`MapCache.modify()` now strips TTL when `timeToLive` is omitted**, matching Redis,
  Memcached, DynamoDB, and the documented contract. Code relying on the old
  preserve-TTL behaviour of the `ram` backend will see entries expire as documented.
