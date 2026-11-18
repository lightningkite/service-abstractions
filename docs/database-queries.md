# Conditions, modifications and optics

The query language behind [Database](database.md) tables, from `database-shared`.  Everything here is a plain serializable value: build it on a client, send it to a server, store it, or evaluate it in memory.

- An **optic** is a typed path into a model: `Post.title`, `Post.address.notNull.city`.
- A **`Condition<T>`** is a filter: `Post.likes gte 10`.
- A **`Modification<T>`** is an update: `Modification<Post> { it.likes += 1 }`.
- A **sort** is a `List<SortPart<T>>`: `sort<Post> { it.postedAt.descending() }`.

Imports: `com.lightningkite.services.database.*` and `com.lightningkite.services.database.optics.*` (for `notNull`, `elements`, `asType`, `raw`, ...).

## Paths

The processor generates a field for every property of a `@Queryable` class:

```kotlin
@Queryable
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val tags: List<String> = listOf(),
    val address: Address? = null,
    val shape: Shape? = null,
) : HasId<Uuid>

@Queryable data class Address(val city: String, val state: String)
@Queryable sealed interface Shape
@Queryable data class Circle(val radius: Double) : Shape

val title: Lens<Post, String> = Post.title
val city: Optic<Post, String> = Post.address.notNull.city
val radius: Optic<Post, Double> = Post.shape.notNull.asCircle.radius
```

- A **`Lens`** always reaches its value.  Fields compose into lenses: `Post.title`, `Order.customer.name`.
- An **`Optic`** may not: `.notNull` (skips nulls), `.elements` (each element of a list or set) and `.asCircle` / `.asType(Circle.serializer())` (one sealed variant) produce optics, whose `get` returns `null` when nothing is there.
- Wrapper types expose their inner value: `.raw` on `EmailAddress` and other `IsRawString`s, `.meters` on `Length`, `.bytes` on `DataSize`, `.kilograms`, `.celsius`, and so on.  Use them to compare or search the wrapped value: `User.email.raw contains "@example.com"`.
- Inside a builder lambda, `it` is the identity lens of the model, so `Condition<Post> { it.title eq "x" }` and `Post.title eq "x"` are the same condition.
- Optics serialize as dotted strings: `"address?.city"`, `"tags.*"`, `"shape.<Circle>"`, and `"this"` for the whole model.

## Conditions

```kotlin
// Comparison
User.email eq me
User.role neq Role.Admin
User.age gt 17;  User.age gte 18;  User.age lt 65;  User.age lte 64   // Comparable and enum fields
User.role inside setOf(Role.Admin, Role.Moderator)
User.role notInside listOf(Role.Member)

// Strings
User.name contains "ada"                          // case-insensitive
User.name.contains("Ada", ignoreCase = false)
User.email.raw contains "@example.com"

// Logic
val adult = User.age gte 18
val staff = (User.role eq Role.Admin) or (User.role eq Role.Moderator)
adult and staff
!staff
Condition.Always
Condition.Never

// Nulls, collections, sealed types
Post.address eq null
Post.address.notNull.city eq "Provo"              // false when address is null
Post.tags.any { it eq "kotlin" }
Post.tags.all { it neq "spam" }
User.shape.notNull.isType(Circle.serializer())
User.shape.notNull.asCircle.radius gt 2.0         // false for other variants

// Geography and bit masks
Post.location.notNull.distanceBetween(GeoCoordinate(40.23, -111.66), lessThan = 10.0.miles)
User.permissions allSet 0b0011;  User.permissions anySet 0b0100
User.permissions allClear 0b1000;  User.permissions anyClear 0b0011
```

Parenthesize the operands of `and`/`or`: all infix functions share one precedence and apply left to right, so `a eq 1 and b eq 2` doesn't parse as intended.  `Condition<User> { it.age gte 18 }` builds a condition in a lambda, `Condition(true)` turns a `Boolean` into `Always`/`Never`.

Build filters from optional inputs with `andNotNull` (and `orNotNull`), which skips nulls:

```kotlin
fun search(minAge: Int?, role: Role?): Condition<User> = Condition.andNotNull(
    minAge?.let { User.age gte it },
    role?.let { User.role eq it },
)
```

A condition is a function of the model and a serializable value:

```kotlin
val matches: Boolean = adult(user)
val json = Json.encodeToString(Condition.serializer(User.serializer()), adult)
```

Full-text search is not a condition: use `Table.findByTextIndex` ([database.md](database.md#indexes-and-text-search)).

## Modifications

```kotlin
Modification<User> {
    it.name assign "Ada"
    it.age += 1                  // increment; *= multiplies
    it.age coerceAtMost 120
    it.age coerceAtLeast 0
}

Modification<Post> {
    it.tags += "kotlin"                            // append one; sets add if absent
    it.tags += listOf("a", "b")                    // or addAll listOf(...)
    it.tags -= "spam"                              // remove every instance
    it.tags removeAll listOf("x", "y")
    it.tags removeAll { tag -> tag contains "temp" }
    it.tags forEach { tag -> tag assign tag }      // per element; forEachIf(condition, modification) for some
    it.address.notNull.city assign "Provo"         // no change when address is null
    it.shape.notNull.asCircle.radius assign 3.0    // no change for other variants
}
```

Several statements in one builder apply together, as one atomic update of each matched row.

There's no append for strings, no drop-first/last for lists, and no per-key map operations: `assign` the new value.  To change a value based on what's there, read it and make the write conditional on what you read (compare-and-swap):

```kotlin
val current = configs.get(id)!!
configs.updateOne(
    condition = Condition<Config> { (it._id eq id) and (it.timeout eq current.timeout) },
    modification = Modification { it.timeout assign current.timeout * 2 },
)
```

On every backend a `notNull` step leaves a null or missing value untouched.  Memory and MongoDB leave other variants untouched for `asType`; Postgres and SQL don't support `asType` modifications yet.

## Sorting

```kotlin
sort<Post> {
    it.likes.descending()
    it.title.ascending(ignoreCase = true)
}
```

Works on `Comparable` and enum fields.  Enum fields sort by name, not declaration order; those calls carry the `@SortByEnumName` opt-in warning to say so.

## Build setup

Apply the KSP plugin and add `database-processor` to the module that declares the models.

**JVM-only module**: nothing else is needed.

```kotlin
dependencies {
    ksp("com.lightningkite.services:database-processor:<version>")
}
```

**Multiplatform module**: declare the models in `commonMain`.  All three pieces are required:

```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            // 1. The processor writes commonMain's fields here, once, for every target to compile.
            kotlin.srcDir(file("build/generated/ksp/common/commonMain/kotlin"))
        }
    }
}

dependencies {
    // 2. The processor runs for commonMain (kspCommonMainMetadata) and for each target.
    configurations.filter { it.name.startsWith("ksp") && it.name != "ksp" }.forEach {
        add(it.name, "com.lightningkite.services:database-processor:<version>")
    }
}

// 3. Only kspCommonMainKotlinMetadata writes commonMain's fields, so every other KSP run and compile waits for it.
tasks.matching { (it.name.startsWith("ksp") || it.name.startsWith("compile")) && it.name != "kspCommonMainKotlinMetadata" }
    .configureEach { dependsOn("kspCommonMainKotlinMetadata") }
```

Without piece 3 the target tasks (`kspKotlinJvm`, ...) fail with "The generated sources for commonMain are missing" (or "out of date").  They deliberately generate nothing for commonMain: they used to each rewrite the shared folder, sometimes under a running compile.  If a target sees none of commonMain's models it can't check, and the symptom is instead every generated field unresolved and an empty `build/generated/ksp/common/commonMain/kotlin`.  Run `./gradlew :module:kspCommonMainKotlinMetadata` to generate by hand.

Only `src/commonMain` and `src/commonTest` count as common.  commonTest has no metadata task: the targets' test KSP tasks write its fields into `build/generated/ksp/common/commonTest/kotlin`, so add that folder to `commonTest` if tests declare models.

What's generated, for `Post`:
- `Post.title`: a `Field<Post, String>` (a `Lens`), plus `Optic`/`Lens` extensions so nested fields chain (`Order.customer.name`).
- `Post.fields`: all fields (skipped if the model has its own `fields` property).
- `asVariant` optics for each leaf of a `@Queryable` sealed type.
- Generic models get serializer extensions instead: `Box.serializer(Int.serializer()).value`.

The generated fields are top-level extensions in the model's package; files in other packages import them by name (`import com.example.title`).

## Gotchas

- **Missing `optics.*` import**: "Unresolved reference 'notNull'" on a `Lens`.  A wildcard `optics.*` import also shadows any local class named `Field`.
- **Deprecated forms**: `User.path.email`, `condition { }`, `modification { }`, `@GenerateDataClassPaths` still compile with warnings.  The replacements are `User.email`, `Condition { }`, `Modification { }`, `@Queryable`.
- **`notNull` on a non-nullable path** compiles, but its serializer throws.
- **Map fields** can't be queried or modified per key; `assign` the whole map.
