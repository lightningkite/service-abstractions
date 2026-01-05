# database-cassandra TODO

Audit performed: 2025-12-23
Last updated: 2025-12-23

## Completed

### ✅ Race condition in insert() - FIXED
Used `INSERT ... IF NOT EXISTS` (lightweight transaction) for atomic duplicate detection.

### ✅ Non-atomic updateOne() - FIXED
Added `atomicUpdate()` helper that uses:
- UPDATE statement when primary key is unchanged
- BATCH (DELETE + INSERT) when primary key changes

### ✅ Non-atomic upsertOne() - FIXED
Now uses `atomicUpdate()` for updates and `INSERT IF NOT EXISTS` for inserts with race recovery.

### ✅ Unbounded OR parallelism - FIXED
Added `Semaphore(MAX_PARALLEL_OR_QUERIES)` to limit concurrent queries to 10.

### ✅ Missing warning logs for full scans - FIXED
Added logging for `analysis.warnings` and `requiresFullScan` in the find() method.

### ✅ Hardcoded replication strategy - FIXED
Added `replicationFactor` parameter to `CassandraDatabase`. Can be set via:
- Constructor parameter
- URL parameter: `cassandra://host/keyspace?rf=3`

### ✅ Unused maxQueryMs parameter - DOCUMENTED
Added `@Suppress("UNUSED_PARAMETER")` and KDoc explaining that Cassandra has its own timeout mechanisms.

---

## Deferred (Lower Priority)

### Prepared statement caching
**File:** `CassandraTable.kt:50`

The `preparedStatements` map is declared but never used. Every query creates a new `SimpleStatement`.

**Impact:** Missed optimization opportunity - prepared statements are faster and reduce CQL parsing overhead.

**Status:** Deferred. Would require significant refactoring since queries are dynamic. The performance impact is minor for most use cases as the driver internally caches query parsing.

---

### Session creation blocking issue
**File:** `CassandraDatabase.kt:165-204`

`CqlSession.builder().build()` is blocking. If called from a coroutine without `Dispatchers.IO`, it blocks the thread pool. The `connect()` method uses `Dispatchers.IO`, but direct access via `table()` doesn't guarantee this.

**Status:** Deferred. The lazy initialization pattern is widely used. Recommend calling `connect()` during application startup.

---

## Testing Gaps

- [ ] No concurrency/race condition tests
- [ ] No stress/load tests
- [ ] No AWS Keyspaces integration tests (Terraform functions marked `@Untested`)
- [ ] No tests for session reconnection after disconnect

---

## Notes

- All tests pass after the fixes
- CQL injection prevention is solid (`CqlSecurityTest.kt`)
- Schema migration works correctly (`SchemaMigrationTest.kt`)
