package com.lightningkite.services.database.mongodb

import com.lightningkite.services.TestSettingContext
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ServerAddress
import com.mongodb.connection.ClusterId
import com.mongodb.connection.ConnectionId
import com.mongodb.connection.ServerId
import com.mongodb.event.ConnectionCheckedInEvent
import com.mongodb.event.ConnectionCheckedOutEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test for the connection-pool utilization counter.
 *
 * The bug being guarded against: `connectionCheckedOut` (a connection taken from the pool for use)
 * was decrementing the in-use counter and `connectionCheckedIn` (a connection returned to the pool)
 * was incrementing it — i.e. the gauge reported *idle* count, inverted from the documented
 * "in-use" semantics used by [MongoDatabase]'s pool health check. After the fix, checking a
 * connection out must increment and checking it in must decrement.
 */
class PoolListenerTest {

    private fun connectionId(): ConnectionId {
        val serverId = ServerId(ClusterId(), ServerAddress("localhost", 27017))
        return ConnectionId(serverId)
    }

    private fun checkedOut(id: ConnectionId) = ConnectionCheckedOutEvent(id, 0L, 0L)
    private fun checkedIn(id: ConnectionId) = ConnectionCheckedInEvent(id, 0L)

    /** Reads the private in-use counter the listener mutates. */
    private fun MongoDatabase.activeCount(): Int {
        val field = MongoDatabase::class.java.getDeclaredField("active").apply { isAccessible = true }
        return (field.get(this) as AtomicInteger).get()
    }

    // The counter test never opens a connection, so use plain settings (no embedded mongo startup);
    // the MongoDatabase client is lazy and is never initialized here.
    private fun newDatabase(): MongoDatabase = MongoDatabase(
        name = "pool-test",
        databaseName = "test",
        clientSettings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString("mongodb://localhost:27017/test"))
            .build(),
        context = TestSettingContext(),
    )

    @Test
    fun checkedOutIncrementsAndCheckedInDecrements() {
        val db = newDatabase()
        val id = connectionId()
        assertEquals(0, db.activeCount(), "starts with no in-use connections")

        db.listener.connectionCheckedOut(checkedOut(id))
        assertEquals(1, db.activeCount(), "checking a connection out marks it in-use")

        db.listener.connectionCheckedOut(checkedOut(id))
        assertEquals(2, db.activeCount(), "a second checkout adds another in-use connection")

        db.listener.connectionCheckedIn(checkedIn(id))
        assertEquals(1, db.activeCount(), "returning a connection frees one in-use slot")

        db.listener.connectionCheckedIn(checkedIn(id))
        assertEquals(0, db.activeCount(), "returning the last connection leaves zero in-use")
    }

    @Test
    fun activeReflectsInUseNotIdle() {
        val db = newDatabase()
        val id = connectionId()

        // Simulate three connections taken for use; the counter must report the in-use count (3),
        // not the number returned/idle (0).
        repeat(3) { db.listener.connectionCheckedOut(checkedOut(id)) }
        assertEquals(3, db.activeCount(), "active must reflect connections currently in use")

        repeat(3) { db.listener.connectionCheckedIn(checkedIn(id)) }
        assertEquals(0, db.activeCount(), "all returned -> zero in use")
    }
}
