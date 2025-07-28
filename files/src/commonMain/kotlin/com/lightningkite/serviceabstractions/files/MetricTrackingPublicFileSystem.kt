package com.lightningkite.serviceabstractions.files

import com.lightningkite.MediaType
import com.lightningkite.serviceabstractions.HealthStatus
import com.lightningkite.serviceabstractions.MetricType
import com.lightningkite.serviceabstractions.measure
import com.lightningkite.serviceabstractions.performanceMetric

/**
 * An abstract base class for FileSystem implementations that tracks metrics.
 */
public abstract class MetricTrackingPublicFileSystem : PublicFileSystem {

    /**
     * Metric types for file operations.
     */
    public val fileListMetric: MetricType.Performance = performanceMetric("list")
    public val fileHeadMetric: MetricType.Performance = performanceMetric("head")
    public val filePutMetric: MetricType.Performance = performanceMetric("put")
    public val fileGetMetric: MetricType.Performance = performanceMetric("get")
    public val fileDeleteMetric: MetricType.Performance = performanceMetric("delete")

    /**
     * The root file object for this file system.
     */
    public abstract override val root: MetricTrackingFileObject
    
    /**
     * A file object that tracks metrics for operations.
     */
    public abstract inner class MetricTrackingFileObject : FileObject {
        
        /**
         * Lists the contents of this directory, or returns null if this is not a directory.
         */
        public final override suspend fun list(): List<FileObject>? {
            return fileListMetric.measure {
                listImpl()
            }
        }
        
        /**
         * Implementation of list() that should be overridden by subclasses.
         */
        protected abstract suspend fun listImpl(): List<FileObject>?
        
        /**
         * Gets information about this file, or null if it doesn't exist.
         */
        public final override suspend fun head(): FileInfo? {
            return fileHeadMetric.measure {
                headImpl()
            }
        }
        
        /**
         * Implementation of head() that should be overridden by subclasses.
         */
        protected abstract suspend fun headImpl(): FileInfo?
        
        /**
         * Writes content to this file.
         */
        public final override suspend fun put(content: TypedData) {
            filePutMetric.measure {
                putImpl(content)
            }
        }
        
        /**
         * Implementation of put() that should be overridden by subclasses.
         */
        protected abstract suspend fun putImpl(content: TypedData)
        
        /**
         * Reads content from this file, or returns null if it doesn't exist.
         */
        public final override suspend fun get(): TypedData? {
            return fileGetMetric.measure {
                getImpl()
            }
        }
        
        /**
         * Implementation of get() that should be overridden by subclasses.
         */
        protected abstract suspend fun getImpl(): TypedData?
        
        /**
         * Deletes this file.
         */
        public final override suspend fun delete() {
            fileDeleteMetric.measure {
                deleteImpl()
            }
        }
        
        /**
         * Implementation of delete() that should be overridden by subclasses.
         */
        protected abstract suspend fun deleteImpl()
    }
    
    /**
     * Checks the health of this file system by writing and reading a test file.
     */
    public override suspend fun healthCheck(): HealthStatus {
        return try {
            val testFile = root.resolve("health-check/test-file.txt")
            val contentData = Data.Text("Test Content")
            val content = TypedData(contentData, MediaType.Text.Plain)
            testFile.put(content)
            val retrieved = testFile.get()
            if (retrieved?.mediaType != MediaType.Text.Plain) {
                HealthStatus(
                    level = HealthStatus.Level.ERROR,
                    additionalMessage = "Test write resulted in file of incorrect content type"
                )
            } else if (retrieved.data.text() != contentData.text()) {
                HealthStatus(
                    level = HealthStatus.Level.ERROR,
                    additionalMessage = "Test content did not match"
                )
            } else {
                testFile.delete()
                HealthStatus(level = HealthStatus.Level.OK)
            }
        } catch (e: Exception) {
            HealthStatus(
                level = HealthStatus.Level.ERROR,
                additionalMessage = e.message
            )
        }
    }
}