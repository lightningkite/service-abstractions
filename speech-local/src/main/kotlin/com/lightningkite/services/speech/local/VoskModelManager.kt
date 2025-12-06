package com.lightningkite.services.speech.local

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

private val logger = KotlinLogging.logger("VoskModelManager")

/**
 * Manages Vosk speech recognition models, including automatic downloading.
 *
 * Vosk requires language models to perform speech recognition. This manager
 * handles downloading and extracting models from the official Vosk repository.
 *
 * ## Default Model
 *
 * By default, uses the small English US model (~40MB):
 * - Model: `vosk-model-small-en-us-0.15`
 * - Languages: English (US)
 * - Size: ~40MB compressed
 *
 * ## Model Storage
 *
 * Models are stored in `./local/vosk-models/` by default.
 *
 * ## Usage
 *
 * ```kotlin
 * val manager = VoskModelManager()
 * val modelPath = manager.ensureModelAvailable()
 * // Use modelPath with Vosk recognizer
 * ```
 */
public class VoskModelManager(
    private val modelsDirectory: File = File("./local/vosk-models"),
    private val defaultModelName: String = DEFAULT_MODEL_NAME
) {
    public companion object {
        /** Default small English model - good balance of size and accuracy */
        public const val DEFAULT_MODEL_NAME: String = "vosk-model-small-en-us-0.15"

        /** Base URL for downloading Vosk models */
        public const val VOSK_MODELS_BASE_URL: String = "https://alphacephei.com/vosk/models"

        /** Available small models for common languages */
        public val AVAILABLE_MODELS: Map<String, String> = mapOf(
            "en-us" to "vosk-model-small-en-us-0.15",
            "en-in" to "vosk-model-small-en-in-0.4",
            "de" to "vosk-model-small-de-0.15",
            "es" to "vosk-model-small-es-0.42",
            "fr" to "vosk-model-small-fr-0.22",
            "it" to "vosk-model-small-it-0.22",
            "pt" to "vosk-model-small-pt-0.3",
            "ru" to "vosk-model-small-ru-0.22",
            "zh" to "vosk-model-small-cn-0.22"
        )
    }

    @Volatile
    private var cachedModelPath: File? = null

    /**
     * Ensures the default model is available, downloading if necessary.
     *
     * @return Path to the model directory
     * @throws VoskModelException if download or extraction fails
     */
    public fun ensureModelAvailable(): File {
        return ensureModelAvailable(defaultModelName)
    }

    /**
     * Ensures a specific model is available, downloading if necessary.
     *
     * @param modelName Name of the Vosk model (e.g., "vosk-model-small-en-us-0.15")
     * @return Path to the model directory
     * @throws VoskModelException if download or extraction fails
     */
    @Synchronized
    public fun ensureModelAvailable(modelName: String): File {
        // Check cache first
        cachedModelPath?.let { cached ->
            if (cached.exists() && cached.name == modelName) {
                return cached
            }
        }

        val modelDir = File(modelsDirectory, modelName)

        if (isValidModelDirectory(modelDir)) {
            logger.info { "Using existing Vosk model at: ${modelDir.absolutePath}" }
            cachedModelPath = modelDir
            return modelDir
        }

        logger.info { "Vosk model not found, downloading: $modelName" }
        downloadAndExtractModel(modelName, modelDir)

        if (!isValidModelDirectory(modelDir)) {
            throw VoskModelException("Downloaded model is invalid: ${modelDir.absolutePath}")
        }

        cachedModelPath = modelDir
        return modelDir
    }

    /**
     * Checks if a model is already downloaded.
     *
     * @param modelName Name of the model to check
     * @return true if the model exists and appears valid
     */
    public fun isModelDownloaded(modelName: String = defaultModelName): Boolean {
        val modelDir = File(modelsDirectory, modelName)
        return isValidModelDirectory(modelDir)
    }

    /**
     * Gets the path where a model would be stored.
     *
     * @param modelName Name of the model
     * @return Path to the model directory (may not exist)
     */
    public fun getModelPath(modelName: String = defaultModelName): File {
        return File(modelsDirectory, modelName)
    }

    /**
     * Deletes a downloaded model to free disk space.
     *
     * @param modelName Name of the model to delete
     * @return true if deletion was successful
     */
    public fun deleteModel(modelName: String = defaultModelName): Boolean {
        val modelDir = File(modelsDirectory, modelName)
        if (cachedModelPath == modelDir) {
            cachedModelPath = null
        }
        return modelDir.deleteRecursively()
    }

    private fun isValidModelDirectory(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false

        // Vosk models should have certain files/directories
        // At minimum, they need am/ (acoustic model) or a model config
        val hasAmDir = File(dir, "am").exists()
        val hasGraphDir = File(dir, "graph").exists()
        val hasConf = File(dir, "conf").exists()
        val hasMfcc = File(dir, "mfcc.conf").exists() || File(dir, "conf/mfcc.conf").exists()

        return hasAmDir || hasGraphDir || hasConf || hasMfcc
    }

    private fun downloadAndExtractModel(modelName: String, targetDir: File) {
        modelsDirectory.mkdirs()

        val zipUrl = "$VOSK_MODELS_BASE_URL/$modelName.zip"
        val tempZip = File(modelsDirectory, "$modelName.zip")

        try {
            logger.info { "Downloading Vosk model from: $zipUrl" }
            downloadFile(zipUrl, tempZip)

            logger.info { "Extracting model to: ${targetDir.absolutePath}" }
            extractZip(tempZip, modelsDirectory)

            logger.info { "Vosk model downloaded and extracted successfully" }
        } catch (e: Exception) {
            // Clean up on failure
            tempZip.delete()
            targetDir.deleteRecursively()
            throw VoskModelException("Failed to download Vosk model: ${e.message}", e)
        } finally {
            tempZip.delete()
        }
    }

    private fun downloadFile(urlString: String, destination: File) {
        val url = URL(urlString)
        val connection = url.openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000 // 5 minutes for large files

        val contentLength = connection.contentLengthLong
        logger.info { "Model size: ${contentLength / 1024 / 1024} MB" }

        connection.getInputStream().use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastLoggedPercent = 0

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    // Log progress every 10%
                    if (contentLength > 0) {
                        val percent = ((totalBytesRead * 100) / contentLength).toInt()
                        if (percent >= lastLoggedPercent + 10) {
                            logger.info { "Download progress: $percent%" }
                            lastLoggedPercent = percent
                        }
                    }
                }
            }
        }
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(targetDir, entry.name)

                // Security check: prevent zip slip vulnerability
                val destDirPath = targetDir.canonicalPath
                val destFilePath = newFile.canonicalPath
                if (!destFilePath.startsWith(destDirPath + File.separator)) {
                    throw VoskModelException("Zip entry is outside of target directory: ${entry.name}")
                }

                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}

/**
 * Exception thrown when Vosk model operations fail.
 */
public class VoskModelException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)
