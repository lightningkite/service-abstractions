package com.lightningkite.services.database.postgres

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Manages a PostgreSQL container with pgvector extension for testing.
 *
 * Uses Testcontainers to automatically start and manage the container lifecycle.
 * The container is shared across all test classes for efficiency.
 *
 * The pgvector/pgvector:pg16 image comes with the pgvector extension pre-installed.
 */
object PostgresDockerContainer {

    /**
     * Custom PostgreSQL container with pgvector support.
     * Uses the official pgvector Docker image which includes the extension pre-installed.
     */
    class PgVectorContainer(dockerImageName: DockerImageName) : PostgreSQLContainer<PgVectorContainer>(dockerImageName) {
        init {
            // Enable the pgvector extension on startup
            withInitScript("init-pgvector.sql")
        }
    }

    @Volatile
    private var container: PgVectorContainer? = null

    @Volatile
    private var started = false

    /**
     * Gets or creates the pgvector container.
     * Thread-safe and idempotent - can be called multiple times.
     *
     * @return The container instance, or null if Docker is not available
     */
    @Synchronized
    fun getContainer(): PgVectorContainer? {
        if (started) return container

        return try {
            val pgvectorImage = DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")

            val newContainer = PgVectorContainer(pgvectorImage)
                .withDatabaseName("test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true) // Reuse container across test runs if possible

            newContainer.start()

            container = newContainer
            started = true

            println("PostgreSQL pgvector container started at ${newContainer.jdbcUrl}")
            newContainer
        } catch (e: Exception) {
            println("Failed to start PostgreSQL pgvector container: ${e.message}")
            println("Docker may not be available. Vector search tests will be skipped.")
            null
        }
    }

    /**
     * Checks if the container is available.
     */
    val isAvailable: Boolean
        get() = getContainer() != null

    /**
     * Gets the JDBC URL for the running container.
     */
    val jdbcUrl: String?
        get() = container?.jdbcUrl

    /**
     * Gets the username for the running container.
     */
    val username: String
        get() = container?.username ?: "test"

    /**
     * Gets the password for the running container.
     */
    val password: String
        get() = container?.password ?: "test"
}
