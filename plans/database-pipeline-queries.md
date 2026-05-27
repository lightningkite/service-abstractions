# Database Pipeline Queries

## Status

Proposal. Not yet implemented.

## Motivation

Today the `Table` interface exposes a small fixed set of read-side primitives:

- `find` / `findPartial` — filter + sort + paginate, returns model rows
- `count` — count matching rows
- `aggregate` — one numeric aggregate over one field
- `groupCount` — count grouped by a single field
- `groupAggregate` — one numeric aggregate grouped by a single field

This covers a lot of CRUD-shaped work but falls off for analytics-shaped questions, especially anything that involves:

- joining one table against another and aggregating the join result onto each row
- grouping by a bucketed key (by month, by power-of-two, by quantized range)
- aggregating non-numeric values (first, last, min, max, set-of, list-of)
- chaining several transforms together (filter → map → filter → group → sort)

The standard workaround is to `find` everything to the application server and process in Kotlin. That works for small tables but is the wrong shape for the database — MongoDB has a powerful native aggregation pipeline that we are unable to take advantage of. It's also extremely easy to get wrong (N+1 query patterns are the default if you write the obvious code).

This proposal introduces a serializable pipeline query DSL that:

1. Compiles to native MongoDB aggregation pipelines where possible.
2. Falls back to a server-side streaming executor for backends without a native implementation, with batched lookups for joins so the fallback is not catastrophic.
3. Reuses the existing type-safe path infrastructure (`DataClassPath`, `Condition`, `SortPart`, `VirtualStruct`) so that pipelines participate fully in the existing query DSL ecosystem.

## Non-Goals

- **Writes through pipelines.** Pipelines produce read-only output. Updates still go through `updateOne` / `updateMany` / etc.
- **PostgreSQL native translation.** Possible later. The default fallback gets Postgres working from day one; native SQL translation can be added when there is a concrete need.
- **Cross-database joins** (e.g. join Mongo table against a Postgres table). Joins are within a single backend.

## Summary

Add `Table.pipeline { ... }` returning `PipelineQuery<O>`, where `O` is the output row type (the original model for shape-preserving pipelines, or a `VirtualInstance` for pipelines that reshape the schema). A pipeline is built by chaining stages, each of which is a serializable data class. Execution is:

- **Native** on backends that implement `Table.executePipeline(...)` (MongoDB at first).
- **Fallback** via `Table.executePipelineDefault(...)` — a default method on `Table` that runs each stage in-process against the model's underlying flow. Join uses key batching.

The fallback is sufficient correctness; the native translation is the performance path.

## API

### Building a Pipeline

The DSL mirrors the user's original sketch, but the dynamic-schema row type is the existing `VirtualInstance` rather than a new `UnknownData` class — reusing the existing virtual-type machinery.

```kotlin
val byMonth: PipelineQuery<VirtualInstance> = userTable.pipeline {
    val xcopy   = def<Int>("xcopy")
    val month   = def<Int>("month")
    val taskSum = def<Double>("taskSum")

    it
        .filter { User.path.age gte 18 }
        .map {
            xcopy assign User.path.age
            month assign User.path.createdAt.month()   // built-in expression
        }
        .filter { xcopy lt 60 }
        .join(
            other = taskTable,
            on = User.path._id,
            otherKey = Task.path.userId,
            additionalFilter = Task.path.completed eq true,
            aggregating = AggregationStrategy.Sum(Task.path.points),
            into = taskSum,
        )
        .group(
            by = month,
            bucketing = BucketingStrategy.Exact,
            aggregations = listOf(
                Aggregation(AggregationStrategy.Average(taskSum), into = def<Double>("avgPoints")),
                Aggregation(AggregationStrategy.Count, into = def<Int>("userCount")),
            ),
        )
        .sort { it.field("month").ascending() }
}

val rows: Flow<VirtualInstance> = byMonth.execute()
```

`it` inside the lambda is the current pipeline (typed as `PipelineBuilder<Model>` initially, `PipelineBuilder<VirtualInstance>` after a `.map` or `.group`). `def<V>(name)` declares a new virtual field that subsequent stages can reference; field names are unique within a pipeline.

### Pipeline and Stages

```kotlin
@Serializable
public data class PipelineQuery<O>(
    public val source: SourceRef,
    public val outputSchema: VirtualStruct?,        // null = output is the source model
    public val stages: List<PipelineStage>,
)

@Serializable
public sealed interface PipelineStage {
    @Serializable
    public data class Filter(val condition: Condition<*>) : PipelineStage

    /**
     * Project the input row into a virtual row described by [outSchema].
     * Each field in [assignments] is a (target field name, expression evaluating against the input row).
     * Fields not listed in [assignments] do not appear in the output.
     */
    @Serializable
    public data class Map(
        val outSchema: VirtualStruct,
        val assignments: List<FieldAssignment>,
    ) : PipelineStage

    @Serializable
    public data class Sort(val orderBy: List<SortPart<*>>) : PipelineStage

    @Serializable
    public data class Skip(val count: Int) : PipelineStage

    @Serializable
    public data class Limit(val count: Int) : PipelineStage

    @Serializable
    public data class Join(
        val otherTable: TableRef,
        val onSourceField: String,                  // field name on current row
        val onOtherPath: DataClassPathPartial<*>,   // path on the joined model
        val additionalFilter: Condition<*>,
        val aggregating: AggregationStrategy<*, *>,
        val into: String,                           // target virtual field name
    ) : PipelineStage

    @Serializable
    public data class Group(
        val outSchema: VirtualStruct,
        val byField: String,                        // virtual field name
        val bucketing: BucketingStrategy<*>,
        val aggregations: List<GroupAggregation>,
    ) : PipelineStage
}

@Serializable
public data class FieldAssignment(
    val targetField: String,
    val expression: PipelineExpression,
)

@Serializable
public data class GroupAggregation(
    val targetField: String,
    val strategy: AggregationStrategy<*, *>,
)
```

`SourceRef` is a serializable reference to a table by name (the same name `Database.table()` uses). `TableRef` is its peer for join targets.

### Expressions

`PipelineExpression` is a small serializable language for the right-hand side of `.map` assignments. To start, only what's needed for the common cases:

```kotlin
@Serializable
public sealed interface PipelineExpression {
    @Serializable public data class Path(val path: DataClassPathPartial<*>) : PipelineExpression     // identity copy
    @Serializable public data class VirtualField(val name: String) : PipelineExpression             // copy a virtual field
    @Serializable public data class Constant(val json: kotlinx.serialization.json.JsonElement) : PipelineExpression
    @Serializable public data class DateComponent(
        val source: PipelineExpression,
        val component: Component,
    ) : PipelineExpression {
        public enum class Component { Year, Month, Day, Hour, Minute, DayOfWeek }
    }
}
```

This is deliberately tiny. Arithmetic and string concatenation can be added when there is demand. Doing less here keeps the native translation manageable on each backend.

### Aggregation Strategies

The existing `Aggregate` enum (Sum/Average/StdDev) is numeric-only and doesn't cover what the pipeline needs. The proposal generalizes it:

```kotlin
@Serializable
public sealed interface AggregationStrategy<In, Out> {
    public val outputType: VirtualTypeReference

    @Serializable public data class Sum(val of: PipelineExpression)      : AggregationStrategy<Number?, Double>
    @Serializable public data class Average(val of: PipelineExpression)  : AggregationStrategy<Number?, Double>
    @Serializable public data class Min<T : Comparable<T>>(val of: PipelineExpression) : AggregationStrategy<T, T>
    @Serializable public data class Max<T : Comparable<T>>(val of: PipelineExpression) : AggregationStrategy<T, T>
    @Serializable public data class First<T>(val of: PipelineExpression) : AggregationStrategy<T, T>
    @Serializable public data class Last<T>(val of: PipelineExpression)  : AggregationStrategy<T, T>
    @Serializable public object Count                                    : AggregationStrategy<Any?, Int>
    @Serializable public data class CollectList<T>(val of: PipelineExpression) : AggregationStrategy<T, List<T>>
    @Serializable public data class CollectSet<T>(val of: PipelineExpression)  : AggregationStrategy<T, Set<T>>
}
```

The existing `Aggregator` interface in `database/Aggregator.kt` is the natural execution primitive for the fallback path, but its `consume(value: Double)` signature is numeric-only. The proposal expands it:

```kotlin
public interface Aggregator2<In, Out> {
    public fun consume(value: In)
    public fun complete(): Out?
}
```

`Aggregator2<Double, Double>` covers everything the old `Aggregator` did; we provide a thin shim so existing call sites (`InMemoryTable.aggregate`, `aggregateOfNotNull`) keep working. Renaming the existing `Aggregator` is the cleaner outcome but is mechanical work that can come last.

### Bucketing Strategies

```kotlin
@Serializable
public sealed interface BucketingStrategy<T> {
    @Serializable public object Exact : BucketingStrategy<Any?>

    @Serializable public data class CustomBoundaries<T : Comparable<T>>(
        val boundaries: List<T>,
    ) : BucketingStrategy<T>

    @Serializable public data class DateTrunc(val component: DateComponent.Component) : BucketingStrategy<kotlin.time.Instant>

    @Serializable public data class Linear<T : Number>(val width: Double, val origin: Double = 0.0) : BucketingStrategy<T>
    @Serializable public data class Log<T : Number>(val base: Double, val origin: Double = 1.0)     : BucketingStrategy<T>
}
```

`Exact` is the existing `groupAggregate` behavior. The others enable analytics queries that today require pulling everything client-side.

## Type System and Schema Tracking

The proposal hinges on representing "the current shape of a row mid-pipeline" in a way that is both type-safe at the Kotlin level and serializable as part of the pipeline. The codebase already has the right primitive: **`VirtualStruct`** (in `database-shared/.../VirtualType.kt`).

- `VirtualStruct` is a serializable description of a struct's fields.
- `VirtualStruct.Concrete` is a `KSerializer<VirtualInstance>` — so virtual rows participate in serialization.
- `VirtualInstance` is the runtime row representation (`List<Any?>` indexed by field index).
- `SerializableProperty.FromVirtualField` already wraps a virtual field as a `SerializableProperty<VirtualInstance, Any?>`, which means **`DataClassPath` already works on virtual rows for free**.

This is what makes the design tractable. The user's `UnknownData` is literally `VirtualInstance`, and `DataClassPath<VirtualInstance, Int>` is constructible today without any new infrastructure.

### Pipeline-builder receiver

`PipelineBuilder<T>` exposes the current row type. Initially `T = Model` (the source table's element type). After a `.map` or `.group`, `T = VirtualInstance` and the builder carries a `VirtualStruct` describing the row.

Inside a stage lambda the user references fields by:

- A generated `DataClassPath` from `@GenerateDataClassPaths` when still working in the model type
- A `def<V>(name)` handle for new virtual fields
- A `field("name")` accessor for previously declared virtual fields

The DSL is mildly weakly-typed once a `.map` has happened — fields can be referenced by name and the compiler can't catch typos at that point. That's the unavoidable cost of dynamic schemas; the alternative (encoding the schema in the type system via phantom type-level lists) is more machinery than this is worth.

### `def` mechanics

`def<V>("xcopy")` does three things atomically:

1. Adds `VirtualField(index = next, name = "xcopy", type = serializerFor<V>())` to the builder's accumulating `VirtualStruct`.
2. Returns a `VirtualHandle<V>` that subsequent stages use to reference the field (carrying name + serializer).
3. Refuses duplicates (`IllegalArgumentException` if `xcopy` already exists).

Field names are required to be syntactically valid identifiers, and `_id` is reserved.

## Execution Model

### Default fallback (`Table.executePipelineDefault`)

Implemented as a default method on the `Table` interface so every backend gets it for free. The fallback walks stages in order:

| Stage    | Streaming? | Memory  | Notes                                                                                                       |
|----------|------------|---------|-------------------------------------------------------------------------------------------------------------|
| Filter   | Yes        | O(1)    | Wraps `find` with the simplifying condition pushed down to the source `find` where possible.                |
| Map      | Yes        | O(1)    | Builds a `VirtualInstance` per row by evaluating each `FieldAssignment.expression`.                         |
| Sort     | No         | O(N)    | Materializes intermediate, sorts, re-emits. Configurable cap; throws above it.                              |
| Skip     | Yes        | O(1)    |                                                                                                             |
| Limit    | Yes        | O(1)    |                                                                                                             |
| Join     | Buffered   | O(B)    | Buffers a batch of B rows, collects distinct join keys, issues one `find(otherKey isIn keys)`, attaches.    |
| Group    | No         | O(K)    | Streams input, maintains one `Aggregator2` per group key. K = distinct group keys.                          |

**Push-down optimization.** If the first stage is `Filter` (and any number of subsequent filters), the fallback merges them into a single `Condition` passed to the source table's native `find()`. This is the difference between scanning the whole table and using indexes — important even for the fallback.

**Join batching.** Batch size defaults to 500 (tunable). For each batch:

1. Materialize the batch of input rows.
2. Collect `distinctKeys = rows.mapNotNull { getJoinKey(it) }.toSet()`.
3. Issue exactly one `otherTable.find(otherKey isIn distinctKeys AND additionalFilter)`.
4. Group results by key and run the `AggregationStrategy` per group.
5. Attach the resulting `into` field to each input row in the batch.
6. Emit.

This turns the obvious N+1 pattern into 1 + ceil(N/B) round trips. For N=10k rows and B=500 that's 21 queries instead of 10000. Without this, the fallback is unusable; with it, it's acceptable for moderate-sized data.

**Caps.** The fallback respects two configurable limits:

- `maxSortRows` (default 100_000) — sort/group buffer cap. Above this the fallback throws `PipelineLimitExceededException`.
- `maxQueryMs` (default 30s) — pipeline-level deadline.

These prevent the fallback from silently destroying a production server when someone runs an analytics query against it.

### Native MongoDB (`MongoTable.executePipeline`)

MongoDB's aggregation pipeline maps to ours almost 1:1:

| Pipeline stage       | Mongo equivalent                         |
|----------------------|------------------------------------------|
| Filter               | `$match`                                 |
| Map                  | `$project` / `$addFields`                |
| Sort                 | `$sort`                                  |
| Skip                 | `$skip`                                  |
| Limit                | `$limit`                                 |
| Join                 | `$lookup` (+ optional `$unwind`/`$group` inside the lookup pipeline for aggregation)         |
| Group (Exact)        | `$group`                                 |
| Group (DateTrunc)    | `$group` with `$dateTrunc` on the `_id`  |
| Group (Linear/Log)   | `$bucketAuto` or `$bucket`               |
| Group (CustomBoundaries) | `$bucket`                            |

The `Condition.bson()` and `Modification.bson()` translators already exist in `database-mongodb`; the pipeline translator reuses them and adds stage-level conversions. The translator returns a `List<Bson>` that `MongoCollection.aggregate(...)` consumes — matching the pattern already used in `MongoTable.find()` for FTS-bearing queries.

`VirtualInstance` results are decoded back through the same path `MongoTable.find` uses, but pointed at the pipeline's `outputSchema` (which is itself a `KSerializer<VirtualInstance>`).

### Other backends

- **InMemoryTable.** Inherits the fallback. No native implementation needed — the fallback runs entirely in-process and is fast for the data sizes InMemory targets.
- **JsonFileTable.** Inherits the fallback.
- **Postgres.** Inherits the fallback initially. A native SQL translation is a future addition: filter/map/sort/skip/limit translate to CTEs cleanly; group is straightforward for `Exact` and `DateTrunc`; bucketing strategies and joins require more care. Defer until there is a concrete pipeline that needs it.

## Performance Characteristics and Warnings

The fallback runs entirely on the application server and pulls intermediate data into application memory. Three specific cases need user awareness:

1. **Sort and group buffer.** A pipeline ending in `group` over a high-cardinality key is O(K) memory. Watching `maxSortRows` is essential.
2. **Join batch reads.** Each join batch is one extra round trip. A pipeline with three joins on 10k rows runs ~60 extra queries on top of the source scan. That's fine but visible in tracing.
3. **Missed pushdown.** If a backend has a native implementation it should always be preferred. Logging when the fallback runs against a backend that *could* support native translation but does not implement it yet would catch surprises in production.

Proposal: emit a structured warning via OpenTelemetry attributes when `executePipelineDefault` is invoked on any table whose backend is `Mongo*` or `Postgres*`. The warning is silent on InMemory/JsonFile where the fallback is the intended path.

## Known Hard Problems

These are the items that need real care during implementation and may surface design adjustments. Surfacing them now so they don't ambush implementation.

### 1. Aggregator interface generalization

The existing `Aggregator` interface is hard-coded to `Double`. Generalizing to `Aggregator2<In, Out>` touches `InMemoryTable.aggregate`, `InMemoryTable.groupAggregate`, the `aggregateOfNotNull` extension, and any downstream calls. Mechanical but invasive. Suggested approach: introduce `Aggregator2` as a new interface, build pipeline aggregators against it, keep the old `Aggregator` as a thin wrapper for source compatibility, schedule a follow-up to migrate the old call sites.

### 2. Determinism of `First` / `Last`

Without a preceding `Sort`, `First`/`Last` are non-deterministic. MongoDB does not guarantee document order without an explicit sort, and the fallback follows the source table's iteration order (also unspecified). **The fallback should hard-error** when `First`/`Last` appears in a group without a preceding sort, rather than silently returning whatever came first. MongoDB will not error in the same case — we accept this asymmetry and document it.

### 3. Security masks across `.map`

`Table.mask()` and `Table.fullCondition()` enforce security rules on the source model. After `.map` reshapes rows into a `VirtualInstance`, the original mask is no longer applicable in its current form — its `DataClassPath<Model, _>` targets reference fields that may no longer exist or that have been renamed.

Two viable answers:

- **(Simple)** Apply masks/condition to the source `find` only — i.e. enforce at the pipeline's entry, never on intermediate rows. Subsequent stages run on already-authorized data. This is what the fallback can implement trivially. It is also what MongoDB will do naturally because `$match` from the mask gets pushed in front of everything else.
- **(Strict)** Disallow pipelines on tables that have post-read masks (`Mask<Model>` that strips fields the pipeline references). Detect at build time by inspecting `mask().paths` against the fields referenced by the pipeline.

Recommendation: implement (Simple). It matches Mongo's natural behavior and is sufficient for the access patterns we have today. Make this an explicit documented choice.

### 4. Index usage with the fallback's filter pushdown

When the fallback pushes a chain of `.filter` calls into the source `find`, the condition passed to `find` should match indexes that exist on the source table. This is automatic if the user wrote `.filter { User.path.email eq x }` (it hits the email index), but `simplify()` on the merged condition can sometimes reorder predicates in ways that obscure index applicability on Postgres. Worth testing once we have backends paying attention to indexes.

### 5. Join key type mismatch

Mongo's `$lookup` requires the join keys on both sides to have comparable BSON types. If `User._id: Uuid` joins to `Task.userId: String` (because of a schema oversight), it silently returns no matches. The fallback would fail in the same way. Build-time check: `onSourceField.serializer.descriptor` must equal `onOtherPath.serializer.descriptor`, otherwise throw `IllegalArgumentException` when the pipeline is constructed.

### 6. `Group` with `BucketingStrategy.Linear`/`Log` on Mongo

`$bucketAuto` chooses bucket boundaries automatically based on data distribution; `$bucket` requires explicit boundaries. Our `Linear`/`Log` strategies specify the *spacing* but not the *range*. Translating to `$bucket` requires knowing the data range, which means a preflight pass (a separate `$group` to get min/max) or accepting `$bucketAuto`'s automatic boundary choice. Easiest: translate `Linear`/`Log` to `$bucket` with a deterministic boundary computation derived from a configurable `(min, max, width|base)`. The user supplies the range explicitly if they want it; otherwise we add a preflight min/max query.

This is the only stage where the API may need to evolve before first use. Worth pinning down before writing the Mongo translator.

## Phased Implementation Plan

Each phase ends with green tests and a usable subset.

### Phase 1: Skeleton + fallback (no joins, no groups)

- `PipelineQuery`, `PipelineStage` sealed hierarchy, `PipelineBuilder<T>` DSL
- `Filter`, `Map`, `Sort`, `Skip`, `Limit`
- `Table.executePipelineDefault` with streaming + filter pushdown
- `Aggregator2` interface alongside existing `Aggregator`
- Tests against `InMemoryTable`

### Phase 2: Aggregation and grouping

- `Group(Exact)` and the full `AggregationStrategy` hierarchy
- `Aggregator2` implementations for every strategy
- `First`/`Last` deterministic-sort guard in the fallback
- Tests covering all strategies against `InMemoryTable`

### Phase 3: Join with batching

- `Join` stage with key-batching executor
- Build-time key type check
- Tests against `InMemoryTable`

### Phase 4: Bucketing

- `DateTrunc`, `CustomBoundaries`, `Linear`, `Log`
- Resolve Mongo `$bucket` range question (see Hard Problem #6) before writing translator

### Phase 5: Native MongoDB translation

- `MongoTable.executePipeline` translating each stage to its Mongo equivalent
- Round-trip tests reusing the same pipeline definitions from Phases 1–4 to verify behavior parity against the fallback

### Phase 6: Telemetry and limits

- `maxSortRows`, `maxQueryMs`, `PipelineLimitExceededException`
- OTel attributes for stage execution times, batch counts, fallback-on-mongo warnings

### Out of scope (later)

- Native Postgres translation
- Writing pipelines back to a table (`pipelineInsertInto`)
- Arithmetic and string-concat expressions
- Window functions

## Open Questions for Discussion

1. **Aggregator2 naming.** Are we comfortable with `Aggregator2` as a transitional name, or do we want to rename the existing `Aggregator` to something more specific (`NumericAggregator`?) and use `Aggregator` for the generic form from day one?
2. **`Group` flattening.** Should `.group { ... }` accept multiple aggregations and produce a single virtual row per group (current proposal), or should the DSL allow nesting (group → group)? The single-level model matches Mongo and SQL — proposed.
3. **`Join` cardinality.** As specified, join always aggregates 1:N matches into a single value via an `AggregationStrategy`. Should we also support a non-aggregating join that produces a list of matched rows attached to a single output field? Useful for "users with their last 5 tasks" patterns; adds complexity. Recommend deferring until concrete demand.
4. **`SourceRef` / `TableRef`.** These are serializable references by name; what registry resolves them at execution time? Proposal: the `Database` instance used to construct the pipeline is the resolver. A pipeline serialized off-process can be re-executed against any `Database` that has the named tables and a matching `SerializersModule`.
