package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Demonstrates the database subsystem: insert/query/update/delete against an in-memory
 * `ram://` database, using a `@GenerateDataClassPaths` model for type-safe conditions and
 * modifications (the KSP-generated `Task.path` companion property).
 */
fun main() = runBlocking {
    val context = TestSettingContext()
    val database = Database.Settings("ram://")("tasks-db", context)
    val tasks = database.prepare(DatabaseTableDefinition<Task>())

    tasks.insert(
        listOf(
            Task(title = "Write demo", done = false),
            Task(title = "Review PR", done = false),
            Task(title = "Ship release", done = true),
        )
    )
    println("All tasks: ${tasks.find(Condition.Always).toList()}")

    val open = tasks.find(Task.path.done eq false).toList()
    println("Open tasks: $open")

    val firstOpen = open.first()
    tasks.updateOneById(firstOpen._id, modification<Task> { it.done assign true })
    println("After completing '${firstOpen.title}': ${tasks.find(Condition.Always).toList()}")

    val removed = tasks.deleteMany(Task.path.done eq true)
    println("Deleted ${removed.size} completed tasks; remaining: ${tasks.find(Condition.Always).toList()}")
}

@GenerateDataClassPaths
@Serializable
data class Task(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val done: Boolean = false,
) : HasId<Uuid> {
    companion object
}
