# Database

A `Database` hands out typed `Table<T>`s of `@Queryable` models.  Queries and updates are `Condition`s and `Modification`s: serializable values that every backend translates to its own query language, and that also run in memory.  The operator reference is [database-queries.md](database-queries.md).

## Modules

| Module | Add it for |
|---|---|
| `database` | `Database`, `Table`, the in-memory backend, permissions, vector indexes.  Multiplatform. |
| `database-shared` | `Condition`, `Modification`, optics, `Query`, `ModelPermissions`.  Client-safe; `database` exposes it. |
| `database-processor` | KSP processor that generates fields for `@Queryable` models.  See [Build setup](database-queries.md#build-setup). |
| `database-mongodb` | MongoDB, Atlas, DocumentDB.  JVM. |
| `database-postgres` | PostgreSQL, with pgvector for vector search.  JVM. |
| `database-sql` | H2, SQLite, MySQL, MariaDB over JDBC.  JVM. |
| `database-sqlite` | `native://`: a SQLite file on the device.  Android, iOS, macOS, JVM, web (OPFS). |
| `database-jsonfile` | One JSON file per table, for local development.  JVM. |
| `database-mapformat` | Internal: flattens models into rows and child tables for the SQL drivers. |
| `database-test` | Shared conformance suites for drivers. |

## Quick start

```kotlin
@Queryable
data class Post(
    override val _id: Uuid = Uuid.random(),
    @Index val author: EmailAddress,
    val title: String,
    val likes: Int = 0,
) : HasId<Uuid>

@Serializable
data class AppSettings(val database: Database.Settings = Database.Settings("ram"))

suspend fun main() {
    MongoDatabase  // registers mongodb:// before settings are parsed
    val settings = Json.decodeFromString<AppSettings>("""{"database": "mongodb://localhost/app"}""")
    val database = settings.database("database", TestSettingContext())
    val posts = database.prepare(DatabaseTableDefinition<Post>())

    val post = posts.insertOne(Post(author = "ada@example.com".toEmailAddress(), title = "Hello"))
    posts.updateOneById(post._id, Modification { it.likes += 1 })
    val popular: List<Post> = posts.find(Post.likes gte 1).toList()
}
```

- `@Queryable` applies `@Serializable` and has the KSP processor generate `Post.title`, `Post.likes`, ...: typed paths for conditions, modifications and sorts.
- Models implement `HasId<ID>` to use the `...ById` helpers.
- `prepare` creates the table and its indexes.  `database.table(def)` returns the table without suspending, for places that can't.  `DatabaseTableDefinition<Post>()` names the table after the class (`"Post"`); pass `name` to choose another.

## Common tasks

### Insert, read, update, delete

```kotlin
posts.insertOne(post)
posts.insert(listOf(a, b))

val one: Post? = posts.get(id)
val some: List<Post> = posts.getMany(ids)
val first: Post? = posts.findOne(Post.author eq ada)
val n: Int = posts.count(Post.likes gt 10)

val change: EntryChange<Post> = posts.updateOne(Post.title eq "Hi", Modification { it.title assign "Hello" })
change.new                                    // null when nothing matched
posts.updateMany(Post.author eq ada, Modification { it.likes assign 0 })
posts.updateOneById(id, Modification { it.likes += 1 })

posts.replaceOneById(id, post.copy(title = "Replaced"))
posts.upsertOneById(id, post)                 // insert or replace
posts.upsertOne(
    condition = Post.title eq "Daily",
    modification = Modification { it.likes += 1 },
    model = Post(author = ada, title = "Daily", likes = 1),   // inserted when nothing matches
)

posts.deleteOneById(id)
val deleted: List<Post> = posts.deleteMany(Post.likes lt 0)
```

Each write has an `...IgnoringResult` / `...IgnoringOld` variant that returns a count or a `Boolean`.  Prefer it when you don't need the rows: some backends must read them back otherwise.

### Sort and page

```kotlin
val page = posts.find(
    condition = Condition.Always,
    orderBy = sort { it.postedAt.descending() },
    skip = 0,
    limit = 20,
).toList()

// Keyset paging scales better than large skips:
val next = posts.find(Post.postedAt lt page.last().postedAt, orderBy = sort { it.postedAt.descending() }, limit = 20).toList()
```

- `find` returns a cold `Flow`; collect it instead of `toList()` to stream large results.
- Queries time out after `maxQueryMs` (default 15 s).
- `sort { it.title.ascending(ignoreCase = true) }` sorts strings case-insensitively.  `listOf(SortPart(Post.likes, ascending = false))` is the same without the DSL.
- `Query(condition, orderBy, skip, limit)` bundles all four for `posts.query(q)`; its `limit` defaults to 100.

### Read only some fields

```kotlin
val partials: List<Partial<Post>> = posts.findPartial(setOf(Post.title, Post.likes), Condition.Always).toList()
```

### Count and aggregate

```kotlin
val total: Double? = posts.aggregate(Aggregate.Sum, property = Post.likes)
val avgByAuthor: Map<EmailAddress, Double?> = posts.groupAggregate(Aggregate.Average, groupBy = Post.author, property = Post.likes)
val perAuthor: Map<EmailAddress, Int> = posts.groupCount(groupBy = Post.author)
```

`Aggregate` is `Sum`, `Average`, `StandardDeviationSample` or `StandardDeviationPopulation`.

### Indexes and text search

```kotlin
@Queryable
@TextIndex(["title", "body"])
@IndexSet(["author", "postedAt"])
data class Post(
    override val _id: Uuid = Uuid.random(),
    @Index(IndexUniqueness.Unique) val slug: String,
    @Index val author: EmailAddress,
    val title: String,
    val body: String,
    val postedAt: Instant,
) : HasId<Uuid>

val hits: List<ScoredResult<Post>> = posts.findByTextIndex("kotlin coroutines", condition = Post.likes gt 0, limit = 10).toList()
hits.forEach { println("${it.score} ${it.model.title}") }
```

`IndexUniqueness` is `NotUnique` (default), `Unique`, or `UniqueNullSparse` (unique among non-null values).  `prepare` creates the indexes.  Text search results come back by relevance, so it takes no `orderBy`.

### Evolve a schema

Add fields with defaults: rows written before decode with the default.  For anything else, migrate with `updateMany`.  Renaming a field, or the table, orphans existing data.

## Permissions

`withPermissions` wraps a table so every call is checked against one user's rights.  Use it for tables clients can reach.

```kotlin
val visible: Table<Post> = posts.withPermissions(
    ModelPermissions(
        create = Post.author eq me,
        read = Condition.Always,
        readMask = mask { it.body.mask("", unless = it.author eq me) },
        update = Post.author eq me,
        updateRestrictions = updateRestrictions { it.author.cannotBeModified() },
        delete = Post.author eq me,
    )
)
```

- Every condition defaults to `Condition.Never`: list what's allowed.  `ModelPermissions.allowAll()` allows everything.
- `readMask` replaces masked fields in results.  It fails closed: conditions, sorts, aggregates and writes that read a masked field only reach rows where that field is unmasked.
- `updateRestrictions { }` limits which fields a write may change and to what: `cannotBeModified()`, `canBeModified()` (switches to whitelist mode), `requires(condition)`, `mustBe { it ... }`.

## Vector search

Similarity search goes through `VectorDatabase`, not `Table`.  A `VectorIndex` holds one `VectorIndexEntry` (`_id` + `embedding`) per indexed row.  Models don't carry embeddings, and searches don't filter: choose which rows are indexed instead.

```kotlin
val vectors: VectorDatabase = VectorDatabase.Settings("postgresql://user:pass@localhost/app")("vectors", context)
val index = vectors.prepare(VectorIndexDefinition("Post", Uuid.serializer(), VectorSpace(EmbeddingModelId("text-embedding-3-small"), 1536)))

val projection = VectorProjection<Post, Uuid>(
    condition = Condition { it.published eq true },
    embed = { rows -> embeddings.embedTexts(rows.map { it.body }, model) },
    reembedWhen = { old, new -> old.body != new.body },
)
val posts = database.prepare(DatabaseTableDefinition<Post>()).vectorIndexed(index, projection)
index.rebuild(posts, projection)  // backfill rows written before indexing

val hits: List<ScoredResult<Uuid>> = index.search(queryEmbedding, limit = 10)
val found = posts.getMany(hits.map { it.model })
```

- **Backends**: `ram` and `json-files` (brute force), `mongodb`/`mongodb+srv` (Atlas `$vectorSearch`, or MongoDB 8.2+ with mongot), `postgresql` (pgvector, HNSW index).  Each of those databases is also a `VectorDatabase`: reuse the `Database` instance rather than opening a second connection pool through `VectorDatabase.Settings`.
- **Syncing**: writes through the wrapped table embed rows that match `condition`, re-embed when `reembedWhen` says so, and remove rows that are deleted or stop matching.  Writes that bypass the wrapper don't.
- **Scores**: cosine similarity mapped to 0..1, `(1 + cos) / 2`.
- **Storage** is named `<name>_<model>_<dimensions>`, so changing the embedding model starts an empty index: `rebuild` it.  Postgres caps that name (plus `_embedding_hnsw`) at 63 characters.
- Embeddings come from the [embedding](embedding.md) service.

## Settings URLs

| Scheme | Module | Example |
|---|---|---|
| `ram` | `database` | `ram` |
| `ram-preload` | `database` | `ram-preload://./seed.json` |
| `delay` | `database` | `delay://100ms-500ms/ram` |
| `mongodb`, `mongodb+srv` | `database-mongodb` | `mongodb://localhost:27017/app` |
| `mongodb-test` | `database-mongodb` | `mongodb-test` (downloads and runs a throwaway MongoDB) |
| `mongodb-file` | `database-mongodb` | `mongodb-file://./local/mongo` (downloads MongoDB, keeps data in the folder) |
| `postgresql` | `database-postgres` | `postgresql://user:pass@localhost:5432/app` |
| `sql-h2`, `sql-sqlite`, `sql-mysql`, `sql-mariadb` | `database-sql` | `sql-h2://mem:test` |
| `native` | `database-sqlite` | `native://app.db` |
| `json-files` | `database-jsonfile` | `json-files://./local/db` |

Parameters and quirks: [Settings URL reference: Database](settings-url-reference.md#database).  Each driver registers its schemes when its class initializes: reference `MongoDatabase`, `PostgresDatabase`, `SqlDatabase`, `SqliteDatabase` or `JsonFileDatabase` once before parsing settings.

Backend notes:
- **MongoDB**: `retryWrites` defaults to false (for DocumentDB); add `retryWrites=true` on Atlas.  `atlasSearch=true` uses Atlas Search for text indexes.
- **Postgres, SQL, SQLite**: each field becomes a column (nested classes flattened, collections in child tables).  `prepare` creates tables and adds columns for new fields.  `asType` modifications on sealed fields aren't supported yet.
- **`native://`**: on Android the location must be absolute (`context.getDatabasePath("app.db").path`); on iOS and macOS a relative path is inside Application Support; on the web it's a file name, and the app must serve SQLite's WebAssembly build (see the `SqliteDatabase` KDoc).

## Testing

Use `ram` in unit tests.  It evaluates every condition and modification in memory with the same semantics as the real backends.

```kotlin
@Test fun likes() = runTest {
    val posts = Database.Settings("ram")("test", TestSettingContext()).prepare(DatabaseTableDefinition<Post>())
    posts.insertOne(Post(author = ada, title = "Hi"))
    assertEquals(1, posts.count(Post.likes eq 0))
}
```

- `delay://100ms-500ms/ram` adds latency, to flush out loading-state bugs.
- `mongodb-test` runs a real, throwaway MongoDB for backend-specific behavior.
- Driver authors: subclass the suites in `database-test` (`ConditionTests`, `ModificationTests`, `SortTest`, ...; the full list is `conformanceSuites`) and assert coverage with `assertConformanceSuitesCovered`.

## Gotchas

- **"No handler mongodb"**: the driver class was never referenced, so its scheme isn't registered.
- **Stale generated code**: KSP caches by a hash of the models.  If generated fields look wrong after a processor upgrade, delete `build/generated/ksp`.
- **Table identity**: definitions with the same `name` are the same table.  Renaming a class without passing the old `name` points at a new, empty table.
- **Timeouts**: unbounded `find`s on big tables hit the 15 s `maxQueryMs`; page them.
- **`json-files`** loads each whole table into memory; it's for local development.
