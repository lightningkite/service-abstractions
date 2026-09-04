package com.lightningkite.services.files.clamav

import com.lightningkite.services.data.DataSize.Companion.mebibytes
import com.lightningkite.services.files.FileScanner
import com.lightningkite.services.test.assertPlannableAwsVpc
import kotlin.test.Test

class TfTest {
    init {
        // Bare reference forces ClamAvFileScanner's companion object to init, registering the clamav://
        // scheme that the terraform helper checks for before emitting anything.
        @Suppress("UNUSED_EXPRESSION")
        ClamAvFileScanner
    }

    @Test
    fun testEc2ClamAv() {
        assertPlannableAwsVpc<FileScanner.Settings>(
            name = "clamav-ec2",
            fulfill = { it.awsEc2ClamAv() }
        )
    }

    @Test
    fun testEc2ClamAvShared() {
        assertPlannableAwsVpc<FileScanner.Settings>(
            name = "clamav-ec2-shared",
            fulfill = {
                it.clamav(
                    awsEc2ClamAv(
                        name = "sharedclamav",
                        instanceType = "t3.small",
                        maxFileSize = 25.mebibytes,
                        maxScanSize = 100.mebibytes,
                        enableSessionManager = false,
                    )
                )
            }
        )
    }
}
