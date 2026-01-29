package com.lightningkite.services.cache.dynamodb

import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import java.io.File
import java.net.URI
import java.net.URL
import java.util.zip.ZipInputStream

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
 * @param port The port for DynamoDB Local to listen on. Default is 7999.
 * @return A [DynamoDbAsyncClient] connected to the local DynamoDB instance.
 *         When [close] is called, the local process is terminated.
 *
 * @see DynamoDbAsyncClientDelegate for the wrapper implementation
 */
// REVIEW NOTE: No error handling if 'java' command is not found or JAR fails to start.
// Thread.sleep(1000L) is a best-effort wait; process may not be ready. Consider
// adding a health check loop or catching process start failures. - by Claude
public fun embeddedDynamo(port: Int = 7999): DynamoDbAsyncClient {
    existingDynamo?.let { return it }
    if(!localFolder.exists()) {
        localFolder.mkdirs()
        logger.info("Downloading local DynamoDB...")
        ZipInputStream(url.openStream()).use {
            while(true) {
                val next = it.nextEntry ?: break
                val dest = localFolder.resolve(next.name)
                if(next.isDirectory) {
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
        .command("java", "-Djava.library.path=./DynamoDBLocal_lib", "-jar", "DynamoDBLocal.jar", "-inMemory", "-port", port.toString())
        .start()
    Thread.sleep(1000L)
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