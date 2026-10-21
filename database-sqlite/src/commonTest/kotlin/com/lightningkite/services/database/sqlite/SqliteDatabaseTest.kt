package com.lightningkite.services.database.sqlite

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Queryable
import com.lightningkite.services.data.Index
import com.lightningkite.services.data.IndexUniqueness
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.lightningkite.services.database.mapformat.ChildTableCollectionHandler
import com.lightningkite.services.database.mapformat.MapFormatConfig
import kotlin.uuid.Uuid

@Queryable
data class Note(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val tags: List<String> = listOf(),
    val labels: Set<String> = setOf(),
    val counts: Map<String, Int> = mapOf(),
    val places: List<Place> = listOf(),
) : HasId<Uuid>

@Queryable
data class Place(val name: String, val visits: Int = 0, val aliases: List<String> = listOf())

/** [Note] as a later version of an app might define it. */
@Queryable
data class NoteWithPriority(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val tags: List<String> = listOf(),
    val labels: Set<String> = setOf(),
    val counts: Map<String, Int> = mapOf(),
    val places: List<Place> = listOf(),
    @Index val priority: Int = 3,
    val archived: Archive? = null,
) : HasId<Uuid>

@Serializable
data class Archive(val reason: String, val notes: List<String>)

@Queryable
data class Account(
    override val _id: Uuid = Uuid.random(),
    @Index(IndexUniqueness.Unique) val email: String,
    @Index(IndexUniqueness.UniqueNullSparse) val handle: String? = null,
) : HasId<Uuid>

@Queryable
data class Membership(override val _id: MembershipKey, val roles: List<String> = listOf()) : HasId<MembershipKey>

@Serializable
data class MembershipKey(val group: String, val user: String) : Comparable<MembershipKey> {
    override fun compareTo(other: MembershipKey): Int = compareValuesBy(this, other, { it.group }, { it.user })
}

// Declared out of alphabetical order, so name order and declaration order differ.
enum class Level { Low, High, Medium }

@Queryable
data class Task(override val _id: Uuid = Uuid.random(), val level: Level) : HasId<Uuid>

class SqliteDatabaseTest {
    private val notes = DatabaseTableDefinition(Note.serializer(), "Note")

    @Test
    fun enumsCompareByDeclarationOrder() = runTest {
        val database = freshDatabase()
        val table = database.prepare(DatabaseTableDefinition(Task.serializer(), "Task"))
        table.insert(Level.entries.map { Task(level = it) })
        assertEquals(Level.entries, table.find(Condition.Always, sort { it.level.ascending() }).toList().map { it.level })
        assertEquals(listOf(Level.Medium), table.find(condition { it.level gt Level.High }).toList().map { it.level })
        table.updateMany(Condition.Always, modification { it.level coerceAtMost Level.High })
        assertEquals(listOf(Level.Low, Level.High, Level.High), table.find(Condition.Always, sort { it.level.ascending() }).toList().map { it.level })
        database.disconnect()
    }

    @Test
    fun survivesReconnecting() = runTest {
        val location = testDatabaseLocation("${Uuid.random()}.db")
        val note = Note(title = "a", tags = listOf("x", "y"), labels = setOf("l"), counts = mapOf("k" to 1), places = listOf(Place("home", 2, listOf("h"))))
        SqliteDatabase("test", location, TestSettingContext()).run {
            prepare(notes).insert(listOf(note))
            disconnect()
        }
        val reopened = SqliteDatabase("test", location, TestSettingContext())
        assertEquals(listOf(note), reopened.prepare(notes).find(Condition.Always).toList())
        reopened.disconnect()
    }

    @Test
    fun newFieldsGetColumnsAndOldRowsTheirDefaults() = runTest {
        val location = testDatabaseLocation("${Uuid.random()}.db")
        val note = Note(title = "a", tags = listOf("x"))
        SqliteDatabase("test", location, TestSettingContext()).run {
            prepare(notes).insert(listOf(note))
            disconnect()
        }
        val reopened = SqliteDatabase("test", location, TestSettingContext())
        val table = reopened.prepare(DatabaseTableDefinition(NoteWithPriority.serializer(), "Note"))
        assertEquals(listOf(NoteWithPriority(note._id, "a", listOf("x"))), table.find(Condition.Always).toList())
        val newer = NoteWithPriority(title = "b", priority = 1, archived = Archive("done", listOf("n")))
        table.insert(listOf(newer))
        assertEquals(listOf(newer), table.find(condition { it.priority eq 1 }).toList())
        // SQL sees the default written into the older row, not a null.
        assertEquals(1, table.count(condition { it.priority eq 3 }))
        table.updateOne(condition { it._id eq note._id }, modification { it.priority += 1 })
        assertEquals(4, table.find(condition { it._id eq note._id }).toList().single().priority)
        reopened.disconnect()
    }

    @Test
    fun uniqueIndexesRejectDuplicates() = runTest {
        val database = freshDatabase()
        val accounts = database.prepare(DatabaseTableDefinition(Account.serializer(), "Account"))
        accounts.insert(listOf(Account(email = "a@x.com"), Account(email = "b@x.com", handle = "b")))
        // Sparse uniqueness lets any number of rows leave the field null.
        accounts.insert(listOf(Account(email = "c@x.com")))
        assertFailsWith<UniqueViolationException> { accounts.insert(listOf(Account(email = "a@x.com"))) }
        assertFailsWith<UniqueViolationException> {
            accounts.updateOne(condition { it.email eq "c@x.com" }, modification { it.handle assign "b" })
        }
        assertFailsWith<UniqueViolationException> {
            accounts.upsertOne(condition { it.email eq "nobody@x.com" }, modification { }, Account(email = "b@x.com"))
        }
        assertEquals(3, accounts.count())
        database.disconnect()
    }

    // A regex can't be checked in SQL, so the page has to be taken after the in-memory filter.
    @Test
    fun pagesConditionsSqlCantCheck() = runTest {
        val database = freshDatabase()
        val table = database.prepare(notes)
        table.insert((0 until 20).map { Note(title = if (it % 2 == 0) "even $it" else "odd $it") })
        val page = table.find(
            path<Note>().title.mapCondition(Condition.RegexMatches("^even")),
            orderBy = sort { it.title.ascending() },
            skip = 2,
            limit = 3,
        ).toList()
        assertEquals(listOf("even 12", "even 14", "even 16"), page.map { it.title })
        database.disconnect()
    }

    @Test
    fun collectionModificationsRunInSql() = runTest {
        val database = freshDatabase()
        val table = database.prepare(notes)
        val note = Note(
            title = "a",
            tags = listOf("x", "y", "z"),
            labels = setOf("p", "q"),
            counts = mapOf("one" to 1, "two" to 2),
            places = listOf(Place("home", 1, listOf("h")), Place("work", 5)),
        )
        table.insert(listOf(note))
        val config = run {
            lateinit var config: MapFormatConfig
            config = MapFormatConfig(TestSettingContext().internalSerializersModule, sqliteConverters, ChildTableCollectionHandler { config })
            config
        }
        val translator = SqliteModifications(database.table(notes).schema, config)
        suspend fun check(modification: Modification<Note>, inSql: Boolean = true) {
            assertEquals(inSql, translator.steps(modification, Note.serializer()).none { it is Step.Rewrite }, "$modification in SQL")
            val before = table.find(Condition.Always).toList().single()
            val change = table.updateOne(Condition.Always, modification)
            assertEquals(before, change.old)
            assertEquals(modification(before), change.new, "after $modification")
            assertEquals(change.new, table.find(Condition.Always).toList().single())
        }
        check(modification { it.tags += listOf("w") })
        check(modification { it.tags.removeAll { it eq "y" } })
        check(modification { it.labels += setOf("q", "r") })
        check(modification { it.labels.removeAll { it eq "p" } })
        check(modification { it.places.forEach { it.visits += 10 } })
        // A collection inside a list element is stored as JSON, so changing it is done in Kotlin.
        check(modification { it.places.forEachIf({ it.name eq "home" }) { it.aliases += listOf("hq") } }, inSql = false)
        check(modification { it.tags assign listOf("only") })
        check(modification { it.labels assign setOf("a", "b") })
        check(modification { it.title assign "b"; it.tags += listOf("again") })
        database.disconnect()
    }

    @Test
    fun compoundIdsOwnTheirChildRows() = runTest {
        val database = freshDatabase()
        val table = database.prepare(DatabaseTableDefinition(Membership.serializer(), "Membership"))
        val a = Membership(MembershipKey("g", "a"), listOf("admin"))
        val b = Membership(MembershipKey("g", "b"), listOf("member"))
        table.insert(listOf(a, b))
        assertEquals(listOf(b), table.find(condition { it.roles.any { it eq "member" } }).toList())
        table.updateOne(condition { it._id eq a._id }, modification { it.roles += listOf("owner") })
        val moved = MembershipKey("h", "a")
        table.updateOne(condition { it._id eq a._id }, modification { it._id assign moved })
        assertEquals(
            setOf(Membership(moved, listOf("admin", "owner")), b),
            table.find(Condition.Always).toList().toSet(),
        )
        database.disconnect()
    }
}
