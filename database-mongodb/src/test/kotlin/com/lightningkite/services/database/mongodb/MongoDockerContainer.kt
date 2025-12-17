package com.lightningkite.services.database.mongodb

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * Manages a MongoDB Atlas Local Docker container for testing using Testcontainers.
 *
 * Uses the mongodb/mongodb-atlas-local image which includes mongod + mongot (search service)
 * pre-integrated for vector search testing.
 *
 * The container is shared across all test classes for efficiency since startup takes ~30 seconds.
 */
object MongoDockerContainer {
    private const val IMAGE = "mongodb/mongodb-atlas-local:8.0.4"
    private const val PORT = 27017

    /**
     * Custom container for MongoDB Atlas Local.
     * Uses GenericContainer since there's no official Testcontainers module for Atlas Local.
     */
    class MongoAtlasLocalContainer(dockerImageName: DockerImageName) :
        GenericContainer<MongoAtlasLocalContainer>(dockerImageName) {

        init {
            withExposedPorts(PORT)
            // Wait for MongoDB to be ready
            waitingFor(
                Wait.forLogMessage(".*Waiting for connections.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(2))
            )
            withReuse(true) // Reuse container across test runs if possible
        }

        /**
         * Gets the connection URL for the running container.
         */
        fun getConnectionUrl(): String {
            return "mongodb://${host}:${getMappedPort(PORT)}/test?directConnection=true"
        }
    }

    @Volatile
    private var container: MongoAtlasLocalContainer? = null

    @Volatile
    private var started = false

    @Volatile
    private var mongotReady = false

    val connectionUrl: String
        get() = container?.getConnectionUrl()
            ?: "mongodb://localhost:$PORT/test?directConnection=true"

    /**
     * Ensures the MongoDB container is running and healthy.
     * Safe to call multiple times - will only start once.
     *
     * @return true if container is available, false if Docker is not available or startup failed
     */
    @Synchronized
    fun ensureStarted(): Boolean {
        if (started && mongotReady) return true

        return try {
            val dockerImage = DockerImageName.parse(IMAGE)
            val newContainer = MongoAtlasLocalContainer(dockerImage)

            println("Starting MongoDB Atlas Local container...")
            newContainer.start()

            container = newContainer
            started = true

            println("MongoDB container started, waiting for mongot (search service)...")

            // Wait for mongot to be ready
            if (waitForMongot()) {
                mongotReady = true
                println("MongoDB Atlas Local container is ready at ${newContainer.getConnectionUrl()}")
                true
            } else {
                println("mongot (search service) failed to become ready")
                false
            }
        } catch (e: Exception) {
            println("Failed to start MongoDB Atlas Local container: ${e.message}")
            println("Docker may not be available. Vector search tests will be skipped.")
            e.printStackTrace()
            false
        }
    }

    /**
     * Wait for mongot (Atlas Search service) to be ready.
     * We do this by attempting to create a search index on a test collection.
     */
    private fun waitForMongot(): Boolean {
        val c = container ?: return false
        val startTime = System.currentTimeMillis()
        val timeoutMs = 90_000L // 90 seconds for mongot

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                // Try to check if mongot is responding by running a search index command
                val result = c.execInContainer(
                    "mongosh", "test", "--quiet", "--eval",
                    """
                    try {
                        db.getCollection('_mongot_ping_test').drop();
                        db.getCollection('_mongot_ping_test').insertOne({test: 1});
                        db.getCollection('_mongot_ping_test').createSearchIndex({
                            name: 'test_index',
                            definition: { mappings: { dynamic: true } }
                        });
                        print('MONGOT_READY');
                    } catch(e) {
                        print('MONGOT_NOT_READY: ' + e.message);
                    }
                    """.trimIndent()
                )

                val output = result.stdout + result.stderr
                if (output.contains("MONGOT_READY")) {
                    // Clean up test collection
                    c.execInContainer(
                        "mongosh", "test", "--quiet", "--eval",
                        "db.getCollection('_mongot_ping_test').drop()"
                    )
                    return true
                }

                println("mongot not ready yet, waiting... (${output.trim()})")
            } catch (e: Exception) {
                println("Error checking mongot status: ${e.message}")
            }

            Thread.sleep(2000)
        }

        return false
    }

    /**
     * Stops the container. Usually not needed as Testcontainers handles cleanup,
     * but useful for explicit cleanup.
     */
    fun stopContainer() {
        container?.stop()
        container = null
        started = false
        mongotReady = false
    }
}
