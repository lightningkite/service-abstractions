package com.lightningkite.services.database.mongodb

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * Manages a DocumentDB local Docker container for testing using Testcontainers.
 *
 * Uses the ghcr.io/documentdb/documentdb/documentdb-local image, which is
 * Microsoft's open-source MongoDB-compatible DocumentDB engine. It behaves like
 * AWS DocumentDB (retryWrites disabled, similar feature set and quirks).
 *
 * The container is shared across all test classes for efficiency.
 */
object DocumentDbDockerContainer {
    private const val IMAGE = "ghcr.io/documentdb/documentdb/documentdb-local:latest"
    private const val PORT = 10260
    private const val USERNAME = "demo"
    private const val PASSWORD = "test"

    class DocumentDbLocalContainer(dockerImageName: DockerImageName) :
        GenericContainer<DocumentDbLocalContainer>(dockerImageName) {

        init {
            withExposedPorts(PORT)
            withCommand("--username", USERNAME, "--password", PASSWORD)
            waitingFor(
                Wait.forLogMessage(".*Gateway is ready on localhost:$PORT.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3))
            )
            withReuse(true)
        }

        fun getConnectionUrl(): String =
            "mongodb://$USERNAME:$PASSWORD@$host:${getMappedPort(PORT)}/test?retryWrites=false&tls=true"
    }

    @Volatile
    private var container: DocumentDbLocalContainer? = null

    @Volatile
    private var started = false

    val connectionUrl: String
        get() = container?.getConnectionUrl()
            ?: "mongodb://$USERNAME:$PASSWORD@localhost:$PORT/test?retryWrites=false&tls=true"

    /**
     * Starts the container if it isn't running yet. Safe to call multiple times.
     *
     * @return true if the container is available, false if Docker is unavailable or startup failed
     */
    @Synchronized
    fun ensureStarted(): Boolean {
        if (started) return true

        return try {
            val newContainer = DocumentDbLocalContainer(DockerImageName.parse(IMAGE))
            println("Starting DocumentDB local container...")
            newContainer.start()
            container = newContainer
            started = true
            println("DocumentDB local container ready at ${newContainer.getConnectionUrl()}")
            true
        } catch (e: Exception) {
            println("Failed to start DocumentDB local container: ${e.message}")
            println("Docker may not be available. DocumentDB tests will be skipped.")
            false
        }
    }
}
