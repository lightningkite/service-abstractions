package com.lightningkite.services.database.mongodb

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import de.flapdoodle.embed.mongo.commands.MongodArguments
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.mongo.transitions.Mongod
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess
import de.flapdoodle.embed.mongo.types.DatabaseDir
import de.flapdoodle.embed.process.io.ProcessOutput
import de.flapdoodle.net.Net
import de.flapdoodle.reverse.TransitionWalker
import de.flapdoodle.reverse.transitions.Start
import org.bson.UuidRepresentation
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

public fun testMongo(
    databaseFolder: File = Files.createTempDirectory("embeddedMongo").toFile(),
    version: String? = null
): MongoClientSettings = embeddedMongo(
    deleteAfter = true,
    databaseFolder = databaseFolder,
    port = Net.freeServerPort(Net.getLocalHost()),
    version = version?.let { Version.Main.valueOf(it) } ?: Version.Main.V8_1
)

public fun embeddedMongo(
    databaseFolder: File = File("./build/embeddedMongo"),
    port: Int? = null,
    version: String? = null
): MongoClientSettings =
    embeddedMongo(
        deleteAfter = false,
        databaseFolder = databaseFolder,
        port = port ?: 54961,
        version = version?.let { Version.Main.valueOf(it) } ?: Version.Main.V8_1
    )

private fun embeddedMongo(
    deleteAfter: Boolean,
    databaseFolder: File,
    port: Int,
    version: Version.Main = Version.Main.V8_1
): MongoClientSettings {

    databaseFolder.mkdirs()
    val runner:TransitionWalker.ReachedState<RunningMongodProcess> = Mongod.instance()
        .withProcessOutput(Start.to(ProcessOutput::class.java).initializedWith(ProcessOutput.named("lsLogger", LoggerFactory.getLogger("de.flapdoodle.embed.mongo"))))
        .withDatabaseDir(Start.to(DatabaseDir::class.java).initializedWith(DatabaseDir.of(databaseFolder.toPath())))
        .withNet(
            Start.to(de.flapdoodle.embed.mongo.config.Net::class.java)
                .initializedWith(
                    de.flapdoodle.embed.mongo.config.Net.defaults()
                        .withPort(port)
                ))
        .withMongodArguments(
            Start.to(MongodArguments::class.java)
                .initializedWith(
                    MongodArguments.defaults()
                        .withUseNoPrealloc(false)
                        .withUseSmallFiles(false)
                        .withUseNoJournal(false)
                        .withIsQuiet(true)
                        .withVerbosityLevel(0)
        ))
        .start(version)
    val connectionString = "mongodb://${runner.current().serverAddress}"

    Runtime.getRuntime().addShutdownHook(Thread {
        runner.current().stop()
        if (deleteAfter) databaseFolder.deleteRecursively()
    })
    return MongoClientSettings.builder()
        .apply {
            applyConnectionString(ConnectionString(connectionString))
            uuidRepresentation(UuidRepresentation.STANDARD)
        }
        .build()

}