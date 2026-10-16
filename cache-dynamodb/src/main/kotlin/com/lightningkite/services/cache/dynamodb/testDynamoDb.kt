package com.lightningkite.services.cache.dynamodb

import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import java.io.File
import java.net.URI
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.zip.ZipInputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val url = URL("https://s3.us-west-2.amazonaws.com/dynamodb-local/dynamodb_local_latest.zip")
private val localFolder = File(System.getProperty("user.home")).resolve(".dynamodblocal")

private val logger = LoggerFactory.getLogger("testDynamoDB")

// REVIEW NOTE: existingDynamo is accessed without synchronization, which could lead to
// race conditions if embeddedDynamo() is called concurrently from multiple threads.
// Consider using a synchronized block or AtomicReference for thread safety. - by Claude
private var existingDynamo: DynamoDbAsyncClient? = null

/**
 * Creates or returns an existing embedded DynamoDB Local instance for testing.
 *
 * Downloads DynamoDB Local from AWS S3 if not already cached in ~/.dynamodblocal,
 * then starts a local DynamoDB process. Uses singleton pattern to reuse existing
 * instance within the same JVM process.
 *
 * Used by `dynamodb-local://` URL scheme in [DynamoDbCache.Companion].
 *
 * Does not return until the local instance is answering requests, so callers never race its startup.
 *
 * @param port The port for DynamoDB Local to listen on. Default is 7999.
 * @return A [DynamoDbAsyncClient] connected to the local DynamoDB instance.
 *         When [close] is called, the local process is terminated.
 * @throws IllegalStateException if the local process exits or does not answer within 30 seconds.
 *
 * @see DynamoDbAsyncClientDelegate for the wrapper implementation
 */
public fun embeddedDynamo(port: Int = 7999): DynamoDbAsyncClient {
    existingDynamo?.let { return it }
    if (!localFolder.exists()) {
        localFolder.mkdirs()
        logger.info("Downloading local DynamoDB...")
        ZipInputStream(url.openStream()).use {
            while (true) {
                val next = it.nextEntry ?: break
                val dest = localFolder.resolve(next.name)
                if (next.isDirectory) {
                    it.closeEntry()
                    continue
                }
                dest.parentFile!!.mkdirs()
                dest.outputStream().use { out ->
                    it.copyTo(out)
                }
                it.closeEntry()
            }
        }
        logger.info("Download complete.")
    }
    val server = ProcessBuilder()
        .directory(localFolder)
        .inheritIO()
        .command(
            "java",
            "-Djava.library.path=./DynamoDBLocal_lib",
            "-jar",
            "DynamoDBLocal.jar",
            "-inMemory",
            "-port",
            port.toString()
        )
        .start()
    val shutdownHook = Thread {
        existingDynamo = null
        try {
            server.destroy()
        } catch (e: Exception) {
            /*squish*/
        }
    }
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    val raw = DynamoDbAsyncClient.builder()
        .region(Region.US_WEST_2)
        .endpointOverride(URI.create("http://localhost:$port"))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummykey", "dummysecret")))
        .build()
    try {
        awaitReady(raw, server, port, 30.seconds)
    } catch (e: Exception) {
        raw.close()
        server.destroy()
        Runtime.getRuntime().removeShutdownHook(shutdownHook)
        throw e
    }
    val newDynamo = object : DynamoDbAsyncClientDelegate(raw) {
        override fun close() {
            super.close()
            existingDynamo = null
            try {
                server.destroy()
            } catch (e: Exception) {
                /*squish*/
            }
        }
    }
    existingDynamo = newDynamo
    return newDynamo
}

/**
 * Blocks until DynamoDB Local answers a request.  Its JVM can take several seconds to start on a busy
 * machine, longer than the client's own retries will wait, so a fixed sleep is not enough.
 */
private fun awaitReady(client: DynamoDbAsyncClient, server: Process, port: Int, timeout: Duration) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (true) {
        if (!server.isAlive) throw IllegalStateException(
            "DynamoDB Local exited with code ${server.exitValue()} before accepting connections on port $port."
        )
        try {
            client.listTables { it.limit(1) }.get()
            return
        } catch (e: ExecutionException) {
            if (deadline.hasPassedNow()) throw IllegalStateException(
                "DynamoDB Local did not accept connections on port $port within $timeout.", e.cause
            )
            Thread.sleep(100L)
        }
    }
}
