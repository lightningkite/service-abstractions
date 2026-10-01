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

## 6. `ModelPermissionsTable` read masks fail closed (LIB-4)

**Was:** a user could learn masked values by filtering inside masked structures, aggregating
the masked field, requesting a nested field via `findPartial`, or reading the count returned by
an update/delete; writes and deletes also reached rows `read` excluded.

**Now:**
- A condition that reads anything a mask may change is limited by the mask's `unless`, so for
  masked users it matches nothing. The check is by path overlap: with `it.embedded.value1` masked,
  filtering on `value1` is restricted and on `value2` is not; with all of `it.embedded` masked, both
  are. A full-text search reads the model's `@TextIndex` fields wherever it sits, since MongoDB runs
  it query-wide; Atlas `$search` now searches only those fields, and without `@TextIndex` it
  searches (and so reads) every field.
- `aggregate`/`groupAggregate` over a masked property (or grouped by one) only include rows where
  the mask's `unless` holds, so masked users get a total over fewer rows (or `null`), with no sign
  that rows were left out.
- Every write and delete only reaches rows matching `read`, and its condition gets the same mask
  check. An upsert whose match is unreadable inserts instead.
- So does what a write's modification reads from the stored row: `forEachIf` filters, `removeAll`
  conditions, and the whole-element matches of `-=` and a set's `+=` (and any `forEach` on a set,
  which merges elements that become equal). With `it.items.elements.secret` masked,
  `items.forEachIf({ it.secret eq "x" }) { ... }` or `items -= item` matches nothing for masked users,
  while `items.forEach { it.other assign ... }` and `items += item` on a list still work.
- Server-defined `requires`/`mustBe` conditions (`ModelPermissions.update`, `UpdateRestrictions`) are
  not masked, so a rule reading a masked field can reveal it through whether a write matched.
- Masks apply to nested `Partial`s. `Modification.invoke(Partial)` hides a value entirely when it
  can't apply a mask field by field (e.g. a per-element mask on a `findPartial` of
  `items.elements.secret`); it used to return the value unchanged.

**Migration:** permission sets that gave `update`/`delete` wider than `read` must widen `read`
or accept the narrower reach.

---

## 7. `notNull` / `asType` modifications are enforced (LIB-2)

**Was:** MongoDB (and Postgres and SQL, for `IfNotNull`) applied the inner modification of
`Modification.IfNotNull` / `IfIsType` unconditionally, writing onto a null, missing or other-variant
value. Guards that predict the stored result with `modification(old)` could be bypassed with a
client-sent `{"field":{"IfNotNull":{"Assign":...}}}` on a null field.
**Now:** every backend leaves a null or missing value untouched, and MongoDB and memory leave another
variant untouched (Postgres and SQL still throw `UnsupportedOperationException` for `asType`).
- MongoDB adds every `notNull`/`asType` check outside lists to the update's filter, so a row failing
  any check is not written and is reported unmatched (`updateOne` returns no change,
  `*IgnoringResult` don't count it; with `orderBy`, the first row passing the checks is updated). An
  upsert still finds such a row, doesn't insert, and reports it unchanged (`EntryChange(row, row)`),
  as the other backends do. Inside `forEach`/`forEachIf`, each check is an array filter.
- Postgres and SQL wrap each written column in `CASE WHEN <field exists> THEN new ELSE old END`, where
  "exists" is the value after the earlier parts of the same modification (so `n assign 3` then
  `n.notNull += 1` stores 4, as in memory).

**Migration:** on MongoDB, a modification mixing checks that disagree on a row (or a check with
unchecked changes) changes nothing on that row, where memory applies the parts whose checks hold;
split it if that matters. To create a null `p`, assign the whole `p`.

---

## 8. `UpdateRestrictions` whitelist matching and `guaranteedAfter` fail closed (LIB-11, LIB-13)

**Was:** a whitelist rule matched a write if the two paths agreed as far as the shorter one went,
so a rule on `a.b` also allowed writing `a` as a whole, and a rule on `items.elements.price`
allowed `items assign`, `+=`, `removeAll` and whole-element assigns. `cannotBeModified()` did
nothing in whitelist mode. `mustBe`/`limitedTo` accepted any sub-field write when the rule wasn't a
plain field check: `it.address.mustBe { (it.state eq "CO") and (it.zip neq "") }` let
`address.state assign "TX"` through, as did rules under `notNull`, an `Or`, a `Not`, or `Never`.

**Now:**
- Whitelist: every path a modification writes needs a rule on it or on a parent of it, or the
  result is `Condition.Never`. A rule on `a.b` doesn't allow `a assign ...`; a rule on list
  elements' fields doesn't allow assigning the list, `+=`, `-=`, `removeAll`, `dropFirst`/`dropLast`,
  or assigning a whole element in `forEach`; replacing the whole row (PUT) is refused. Writes through
  `asType` match the variant's field (`it.thing.asFoo.name`). A modification that writes nothing
  returns `Always`.
- In both modes, every rule on a field the modification writes applies, including rules on
  children of a parent being replaced, and `cannotBeModified()` refuses the field in whitelist mode
  too: `it.a.canBeModified(); it.a.secret.cannotBeModified()` allows `a.other`, not `a.secret` or `a`.
- A `mustBe`/`limitedTo` rule is checked with the new `Condition.requiredBefore(modification)`: what
  the row must meet before the write for the rule to hold after it, which `UpdateRestrictions` adds
  to the update's condition. An assign is checked directly. Otherwise `And` is split part by part,
  and a part the write leaves alone (it reads none of the written fields, or is `neq null` under any
  write but assigning null) must already hold: with
  `address.mustBe { (it.state eq "CO") and (it.zip neq "") }`, `address.state assign "CO"` requires
  `address.zip neq ""`. Writes under `notNull`/`asType` require a non-null value or that variant, and
  `forEachIf` requires the rule of elements it skips; a write under another variant than the rule's
  requires the rule as it stands (so `thing.mustBe { it.asFoo.name eq "a" }` stops
  `thing.asBar.id assign ...` changing a `Bar`). Anything else it can't prove is refused, e.g. an
  `Or` or `Not` over the written sub-field (`address.mustBe { (it.state eq "CO") or (it.zip eq "") }`
  refuses `address.state assign ...`; assign the whole `address` instead). `eq null` gets no
  "unchanged" rule. `guaranteedAfter` is now `requiredBefore(...) != Condition.Never`.
- `affects()` looks through `asType`: a write to `thing.asFoo.name` doesn't trigger a rule on, or a
  mask's sort check for, `thing.asBar.id`. `Modification.Nothing` affects nothing.

**Migration:** allow the parent (`it.a.canBeModified()`) where clients replace it, create or clear
it, or edit a list of it (the LS-KiteUI diff assigns whole lists, sets, maps, sealed values and
null↔object changes), and use `cannotBeModified()` for children that must stay fixed. Rewrite
compound `mustBe` conditions as an `And` of per-field checks, or put the rule on the field itself.

**Still coarse:** paths don't record `notNull`, `asType` or list elements, so a rule ending at one
of those (`it.x.notNull`, `it.thing.asFoo`, `it.items.elements`) covers the whole field: it allows
`x assign null`, replacing `thing` with another variant, or `items += ...`. Put rules on a field
below the step when that matters.

---

## 9. `notNull` and map-key conditions no longer match null or missing values (LIB-16)

**Was:** MongoDB dropped the null check of `Condition.IfNotNull`, so a `notNull` path followed by
something that accepts null (`neq`, `notInside`, `not`, `Always`) also matched documents where the
field was null or missing: `it.owner.notNull neq bannedId` matched ownerless rows. In memory
`notNull` never matches null, so permission conditions (`read`/`update`/`delete`, mask `unless`)
granted more in MongoDB than websockets and mask checks did. `OnKey` (a map-key condition) had the
same gap for a missing key on MongoDB and Postgres. The Cassandra normalizer rewrote
`!(x.notNull ...)` and `!(map[k] ...)` so they no longer matched null or a missing key.
**Now:** these queries no longer match a null value or a missing field or key.
- MongoDB: when the inner condition could match null (`neq`, `notInside`, `not`, `Always`, `all { }`,
  ...), `IfNotNull` adds `{field: {$ne: null}}` (`{field: {$type: "array"}}` for a list or set, since
  `$ne: null` rejects an array holding a null) and `OnKey` adds `{key: {$exists: true}}`; other
  queries keep their shape. A `notNull` element condition built from `and`/`or` inside `any { }` or
  `all { }` (e.g. `it.notNull.mapCondition(And(...))`) still isn't supported: MongoDB rejects it.
- Postgres: `OnKey` also requires the key to be present.
- SQL: `OnKey` on a map of non-`String` values no longer throws a `ClassCastException`.

**Migration:** none needed, but queries that relied on the extra matches now return fewer rows.
If a missing field should count, say so: `(it.x eq null) or (it.x.notNull neq v)`, or just
`it.x neq v`.

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
