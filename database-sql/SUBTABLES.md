# `database-sql`: How Collections Become Child Tables

The generic SQL driver (`database-sql`, backed by [Exposed](https://github.com/JetBrains/Exposed))
targets *any* SQL database — H2, PostgreSQL, MySQL, SQLite, etc. Unlike MongoDB, a portable SQL
schema has no native array, list, set, or map column type. So when a model field is a collection,
the driver cannot store it in a column on the model's own row.

Instead, every collection field is **flattened out into its own table** — a *child table* (a.k.a.
*subtable*). Each element of the collection becomes one row in that child table, linked back to the
parent row by a foreign key. This is the classic relational "one-to-many via a join table" pattern,
generated automatically from your `@Serializable` model.

This document explains the rules, walks through a full worked example (model → tables → rows), and
shows how queries and updates flow through the child tables.

---

## 1. The mapping rules

When `SqlSchema` walks your model's `SerialDescriptor`, it sorts every field into one of two
destinations:

| Field kind | Destination |
|---|---|
| Scalar (`Int`, `String`, `Uuid`, `Instant`, enums, …) | A **column on the main table** |
| Embedded class (a nested `@Serializable` data class) | **Flattened into columns on the main table**, names joined with `__` |
| Nullable embedded class | Same, plus an `<prefix>__exists` boolean column |
| `List<T>` / `Set<T>` | A **child table** |
| `Map<K, V>` | A **child table** (with key column(s)) |

So: **scalars and embedded classes stay on the main row; lists, sets, and maps go to child tables.**

### Naming conventions

- **Main table name** = the collection name you pass to `database.collection<T>("name")`, with any
  `.` replaced by `__`.
- **Child table name** = `<mainTableName>__<fieldPath>`, where `fieldPath` is the field's path from
  the root with `__` separators. A list nested inside an embedded class (`address.phones`) produces
  `<main>__address__phones`.
- **Flattened embedded columns** = the field path joined with `__` (e.g. `address.city` →
  `address__city`).

### Child table structure

Every child table has the same skeleton, plus columns derived from the element type:

| Column | Purpose | Present when |
|---|---|---|
| `owner_id` | Foreign key back to the parent's `_id` (same column type) | Always |
| `idx` | Integer ordinal — preserves list order; also the map-entry ordinal | **Ordered** collections only: lists and maps. **Omitted for sets** (see below) |
| `key` *(or flattened key columns)* | The map key | Maps only |
| `value` | The element, for **primitive** element types | Lists/sets of primitives, map values that are primitives |
| *(one column per field)* | The element's fields, for **struct** element types | Lists/sets/maps whose element is an embedded class |

> **Sets have no `idx` column.** Order is not part of a `Set`'s contract — using a set is precisely
> how you communicate that order is irrelevant — so the driver deliberately stores no ordinal for
> set child tables. (kotlinx.serialization reports both `List` and `Set` as `StructureKind.LIST`;
> the driver distinguishes them by the collection serializer's `serialName`, e.g.
> `kotlin.collections.LinkedHashSet`.) On read, set rows come back in whatever order the database
> returns them, which is correct: nothing should depend on it.

The driver decides between a single `value` column and per-field columns by inspecting the element
descriptor: a primitive/enum element gets one `value` column; an embedded-class element gets one
column per field (named after the field, e.g. `title`, `year`).

### Auto-generated indexes

In `SqlSchema.init`:

- Every child table gets an index on `owner_id` (for the join back to the parent).
- A list/set of a **single primitive** also gets an index on its `value` column — this makes
  membership queries (`tags.any { it eq "kotlin" }`) fast.
- Maps get only the `owner_id` index.

### The foreign key

`childTable.foreignKey(owner_id to mainTable._id)` is declared, so the child rows reference the
parent. Note the driver also **deletes children explicitly** before deleting a parent (see §5), so
it does not rely on `ON DELETE CASCADE` being honored by every backend.

> **Limitation — single-column `_id` required.** `createChildTable` looks up `mainTable.col["_id"]`
> and throws if it's absent. A model with a *compound* `_id` (an embedded class as the primary key,
> which the driver stores as `_id__a`, `_id__b`, …) **cannot also have collection fields**. Compound
> keys and child tables are mutually exclusive in the current implementation.

---

## 2. Full worked example

### The model

```kotlin
@GenerateDataClassPaths
@Serializable
data class Author(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val age: Int,
    val address: Address = Address(),          // embedded → flattened onto main table
    val tags: List<String> = listOf(),         // list of primitive → child table, `value` column
    val books: List<Book> = listOf(),          // list of struct → child table, per-field columns
    val ratings: Map<String, Int> = mapOf(),   // map → child table, `key` + `value` columns
) : HasId<Uuid>

@Serializable
data class Address(
    val street: String,
    val city: String,
)

@Serializable
data class Book(
    val title: String,
    val year: Int,
)
```

Created with:

```kotlin
val authors = database.collection<Author>("Author")
```

### The generated tables

**Main table `Author`** — scalars and the flattened `Address`:

```sql
CREATE TABLE "Author" (
    "_id"            UUID PRIMARY KEY,
    "name"           TEXT NOT NULL,
    "age"            INT  NOT NULL,
    "address__street" TEXT NOT NULL,
    "address__city"   TEXT NOT NULL
);
```

Note that `tags`, `books`, and `ratings` are **absent** from the main table — they live in child
tables.

**Child table `Author__tags`** — `List<String>`, element is a primitive, so a single `value`
column:

```sql
CREATE TABLE "Author__tags" (
    "owner_id" UUID NOT NULL,
    "idx"      INT  NOT NULL,
    "value"    TEXT NOT NULL,
    CONSTRAINT fk_tags_owner FOREIGN KEY ("owner_id") REFERENCES "Author"("_id")
);
CREATE INDEX ON "Author__tags" ("owner_id");
CREATE INDEX ON "Author__tags" ("value");   -- single-primitive list → value indexed
```

**Child table `Author__books`** — `List<Book>`, element is a struct, so one column per `Book` field:

```sql
CREATE TABLE "Author__books" (
    "owner_id" UUID NOT NULL,
    "idx"      INT  NOT NULL,
    "title"    TEXT NOT NULL,
    "year"     INT  NOT NULL,
    CONSTRAINT fk_books_owner FOREIGN KEY ("owner_id") REFERENCES "Author"("_id")
);
CREATE INDEX ON "Author__books" ("owner_id");   -- no value index (element isn't a single primitive)
```

**Child table `Author__ratings`** — `Map<String, Int>`, primitive key + primitive value:

```sql
CREATE TABLE "Author__ratings" (
    "owner_id" UUID NOT NULL,
    "idx"      INT  NOT NULL,
    "key"      TEXT NOT NULL,   -- the map key
    "value"    INT  NOT NULL,   -- the map value
    CONSTRAINT fk_ratings_owner FOREIGN KEY ("owner_id") REFERENCES "Author"("_id")
);
CREATE INDEX ON "Author__ratings" ("owner_id");
```

### A row, fanned out

Given this entity:

```kotlin
Author(
    _id = uuid("11111111-..."),
    name = "Ursula",
    age = 81,
    address = Address(street = "1 Sea Rd", city = "Portland"),
    tags = listOf("scifi", "fantasy"),
    books = listOf(
        Book("A Wizard of Earthsea", 1968),
        Book("The Left Hand of Darkness", 1969),
    ),
    ratings = mapOf("editor" to 5, "critic" to 4),
)
```

it is stored as **one main row plus several child rows**, all keyed by `owner_id = 11111111-...`:

`Author`
| _id | name | age | address__street | address__city |
|---|---|---|---|---|
| 11111111-… | Ursula | 81 | 1 Sea Rd | Portland |

`Author__tags`
| owner_id | idx | value |
|---|---|---|
| 11111111-… | 0 | scifi |
| 11111111-… | 1 | fantasy |

`Author__books`
| owner_id | idx | title | year |
|---|---|---|---|
| 11111111-… | 0 | A Wizard of Earthsea | 1968 |
| 11111111-… | 1 | The Left Hand of Darkness | 1969 |

`Author__ratings`
| owner_id | idx | key | value |
|---|---|---|---|
| 11111111-… | 0 | editor | 5 |
| 11111111-… | 1 | critic | 4 |

The link is always the same: **`child.owner_id == main._id`**, and `idx` preserves order on read
(`ORDER BY idx ASC`).

---

## 3. The encode/decode bridge: `ChildRow`

The driver doesn't hand-write this fan-out per model. It reuses the shared `MapFormat` serializer
from the `database` module, plugging in a `ChildTableCollectionHandler` that turns every
collection element into a `ChildRow`:

```kotlin
class ChildRow(
    val index: Int?,                  // → the `idx` column
    val key: Map<String, Any?>?,      // → the key column(s), maps only
    val values: Map<String, Any?>,    // → the element/value column(s)
)
```

- **Encoding** (`SqlMapFormat.encode`) produces a `WriteResult(mainRecord, children)`. `mainRecord`
  becomes the main row; `children` is a `Map<fieldPath, List<ChildRow>>`.
  - A primitive element → `values = {"value": v}`.
  - A struct element → `values =` the element's own flattened field map (e.g. `{"title": …, "year": …}`).
  - A map entry → `key = {"key": k}` (or flattened struct key) and `values = {"value": v}`.
- **Decoding** reverses it: `SqlCollection.fetchChildRows` reads the child tables `WHERE owner_id IN
  (...) ORDER BY idx`, rebuilds `ChildRow`s, groups them by `owner_id`, and feeds them back into
  `MapFormat.decode` alongside the main row.

So the column layout in §2 is exactly the `ChildRow` shape projected onto SQL columns.

---

## 4. Nested collections inside a child element → JSON text

Child tables are **one level deep**. If an element type *itself* contains a list or map, that inner
collection is **not** given a third table — it is stored as a JSON-encoded `TEXT` column in the
child table (see `registerChildColumns`: a `LIST`/`MAP` field inside a child element becomes a
`TextColumnType`).

```kotlin
@Serializable
data class Book(
    val title: String,
    val year: Int,
    val chapters: List<String> = listOf(),   // nested list inside a child element
)
```

`Author__books` then has a `chapters` column of type `TEXT` holding the serialized list, rather than
a fourth table. Queries cannot reach inside that JSON column (they fall back to a table scan — see
§6).

---

## 5. Write path (insert / update / delete)

All in `SqlCollection`:

- **Insert** — insert the main row, then `insertChildren(ownerId, writeResult)` `batchInsert`s each
  `ChildRow` into its child table, setting `owner_id`, `idx`, any `key` columns, and the value
  column(s).
- **Update / replace / upsert** — `writeEntity` updates the main row's scalar columns, then
  **deletes all child rows for that `owner_id` and re-inserts them** from the new value. Collections
  are always rewritten wholesale, not diffed.
- **Delete** — `deleteChildren(ownerId)` removes the child rows first, then the main row is deleted.
  (Explicit, so it doesn't depend on `ON DELETE CASCADE`.)

### Scalar fast-path vs. read-modify-write

`updateOneIgnoringResult` / `updateManyIgnoringResult` check `Modification.isScalarOnly(schema)`:

- If the modification touches **only main-table scalar columns** *and* the model has **no child
  tables at all**, the driver issues a single efficient `UPDATE … SET …`.
- Otherwise (any collection modification, or any model that *has* child tables and is fully
  reassigned), it falls back to **read-modify-write**: load the entity, apply the modification in
  memory, then `writeEntity` (update main + delete/reinsert children).

This is why collection modifications like `ListAppend`, `SetRemove`, `Combine`, `ModifyByKey`, etc.
are reported as *not* scalar-only — they require the read-modify-write path.

---

## 6. Querying against child tables

Conditions on a collection field are detected in `SqlConditionMapping`: when a `Condition.OnField`
navigates to a path that is a registered child table, it routes to `conditionOnChildTable`, which
emits a correlated subquery instead of a column comparison.

| Condition (DSL) | Emitted SQL shape |
|---|---|
| `tags.any { it eq "scifi" }` (`ListAnyElements` / `SetAnyElements`) | `EXISTS (SELECT 1 FROM Author__tags WHERE owner_id = Author._id AND value = 'scifi')` |
| `tags.all { it neq "x" }` (`ListAllElements` / `SetAllElements`) | `NOT EXISTS (SELECT 1 FROM … WHERE owner_id = Author._id AND NOT (<cond>))` |
| `books.size eq 2` (`ListSizesEquals` / `SetSizesEquals`) | `(SELECT COUNT(*) FROM Author__books WHERE owner_id = Author._id) = 2` |
| `ratings.containsKey("editor")` (`Exists`, maps) | `EXISTS (SELECT 1 FROM Author__ratings WHERE owner_id = Author._id AND key = 'editor')` |
| `ratings.get("editor") { it gt 3 }` (`OnKey`, maps) | `EXISTS (… WHERE owner_id = … AND key = 'editor' AND value > 3)` |

The inner condition is built against an *element field set* derived from the child table's columns
(single-primitive elements expose the `value` column as the "self" field; struct elements expose
their per-field columns).

### Table-scan fallback

The condition mapper tracks an `isExact` flag on `SqlConditionContext`. Anything it can't translate
to SQL — a condition on a whole-collection `Equal`, a collection condition on a non-child-table
field, full-text/geo search, conditions reaching into a JSON-encoded nested collection (§4) — sets
`isExact = false` and maps to `TRUE`/`FALSE`. The driver then loads the candidate rows (with their
children) and **re-filters them in memory** with the original predicate. Correct, but not
index-accelerated, so prefer the supported shapes above when performance matters.

---

## 7. Quick reference

```
Model field                         →  Storage
─────────────────────────────────────────────────────────────────────
scalar / enum                       →  main table column
embedded class                      →  main table columns, "parent__child"
nullable embedded class             →  + "parent__exists" boolean
List<primitive>                     →  child table  <main>__<field>(owner_id, idx, value)
Set<primitive>                      →  child table  <main>__<field>(owner_id, value)          ← no idx
List<struct>                        →  child table  <main>__<field>(owner_id, idx, <fields…>)
Set<struct>                         →  child table  <main>__<field>(owner_id, <fields…>)      ← no idx
Map<K, primitive>                   →  child table  <main>__<field>(owner_id, idx, key, value)
Map<K, struct>                      →  child table  <main>__<field>(owner_id, idx, key, <fields…>)
collection nested inside a child    →  JSON TEXT column on the child table (no extra table)
─────────────────────────────────────────────────────────────────────
link: child.owner_id  ==  main._id    order: ORDER BY idx ASC (ordered collections only; sets unordered)
constraint: requires a single-column _id  (no compound/embedded primary keys with collections)
```

### Relevant source files

- `SqlSchema.kt` — schema generation; main table + child table column layout, indexes, FKs.
- `ChildTableCollectionHandler.kt` — serializer hook that turns collection elements into `ChildRow`s.
- `SqlCollection.kt` — read/write paths: `fetchChildRows`, `insertChildren`, `deleteChildren`,
  `writeEntity`, and the scalar-update fast-path.
- `SqlConditionMapping.kt` — `conditionOnChildTable` and the `EXISTS`/`COUNT` subquery emission.
- `database/.../mapformat/WriteTarget.kt` — `ChildRow` / `WriteResult` definitions.
</content>
</invoke>
