package com.lightningkite.services.files.clamav

import com.lightningkite.services.data.DataSize
import com.lightningkite.services.data.DataSize.Companion.gibibytes
import com.lightningkite.services.data.DataSize.Companion.mebibytes
import com.lightningkite.services.files.FileScanner
import com.lightningkite.services.test.TerraformEmitterAwsTestWithVpc
import com.lightningkite.services.test.assertPlannableAwsVpc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
                    )
                )
            }
        )
    }

    @Test
    fun testEc2ClamAvWithSwap() {
        assertPlannableAwsVpc<FileScanner.Settings>(
            name = "clamav-ec2-swap",
            fulfill = { it.awsEc2ClamAv(instanceType = "t3.small", swapSize = 2.gibibytes) }
        )
    }

    @Test
    fun testEc2ClamAvUbuntu2604() {
        assertPlannableAwsVpc<FileScanner.Settings>(
            name = "clamav-ec2-ubuntu2604",
            fulfill = { it.awsEc2ClamAv(distro = ClamAvDistro.Ubuntu2604) }
        )
    }

    @Test
    fun testEc2ClamAvAmazonLinux() {
        assertPlannableAwsVpc<FileScanner.Settings>(
            name = "clamav-ec2-al2023",
            fulfill = { it.awsEc2ClamAv(distro = ClamAvDistro.AmazonLinux2023) }
        )
    }

    @Test
    fun testEc2ClamAvAmazonLinuxArmWithSwap() {
        assertPlannableAwsVpc<FileScanner.Settings>(
            name = "clamav-ec2-al2023-arm",
            fulfill = {
                it.awsEc2ClamAv(
                    instanceType = "t4g.small",
                    architecture = "arm64",
                    swapSize = 2.gibibytes,
                    distro = ClamAvDistro.AmazonLinux2023,
                )
            }
        )
    }

    private fun userData(
        swapSize: DataSize? = null,
        extra: String = "",
        distro: ClamAvDistro = ClamAvDistro.Ubuntu2404,
    ) = clamAvUserData(
        port = 3310,
        maxFileSize = 100.mebibytes,
        maxScanSize = 400.mebibytes,
        maxThreads = 12,
        extra = extra,
        swapSize = swapSize,
        distro = distro,
    )

    /** Runs [awsEc2ClamAv] against a real emitter; no terraform process is involved. */
    private fun emitter(
        swapSize: DataSize? = null,
        rootVolumeSizeGb: Int = 20,
        distro: ClamAvDistro = ClamAvDistro.Ubuntu2404,
        architecture: String = "amd64",
    ): TerraformEmitterAwsTestWithVpc<FileScanner.Settings> {
        val emitter = TerraformEmitterAwsTestWithVpc(
            File("build/test/clamav-ec2-validation"),
            "test",
            serializer<FileScanner.Settings>(),
        )
        with(emitter) {
            awsEc2ClamAv(
                name = "clamav",
                swapSize = swapSize,
                rootVolumeSizeGb = rootVolumeSizeGb,
                distro = distro,
                architecture = architecture,
            )
        }
        return emitter
    }

    private fun emit(swapSize: DataSize, rootVolumeSizeGb: Int = 20): Map<String, String> =
        emitter(swapSize = swapSize, rootVolumeSizeGb = rootVolumeSizeGb).extraFiles

    private fun emittedTerraform(distro: ClamAvDistro, architecture: String): String =
        emitter(distro = distro, architecture = architecture).files.values.joinToString("\n") { it.toJsonObject().toString() }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun ubuntuWithoutSwapIsUnchanged() {
        // The instance has user_data_replace_on_change, so any change to the default script replaces every
        // existing host. This is the hash of the script generated before swap and distro support were added;
        // only change it on purpose, and call the replacement out in the changelog.
        assertEquals(
            "cf1ac1ca0d0f55386bb733c1baacf7c28f37e7c63ac809478a17adf05ba0ba9f",
            sha256(userData()),
        )
    }

    @Test
    fun shebangIsTheFirstLine() {
        for (distro in ClamAvDistro.entries) for (swapSize in listOf(null, 2.gibibytes)) {
            assertTrue(userData(swapSize = swapSize, distro = distro).startsWith("#!/bin/bash\n"), "$distro $swapSize")
        }
    }

    @Test
    fun noSwapByDefault() {
        val script = userData()
        assertFalse("swap" in script)
        // The seam the swap block is spliced into. Any change to these bytes would replace existing hosts,
        // because the instance has user_data_replace_on_change.
        assertContains(script, "export DEBIAN_FRONTEND=noninteractive\n\n# cloud-init can win the race")
    }

    @Test
    fun swapIsSizedInMebibytesAndSurvivesReboot() {
        val script = userData(swapSize = 2.gibibytes)
        assertContains(script, "\nfallocate -l 2048M /swapfile\n")
        assertContains(script, "\nmkswap /swapfile\n")
        assertContains(script, "\nswapon /swapfile\n")
        assertContains(script, "\necho '/swapfile none swap sw 0 0' >> /etc/fstab\n")
    }

    @Test
    fun swapIsActiveBeforeTheDatabaseLoads() {
        val script = userData(swapSize = 2.gibibytes)
        assertTrue(script.indexOf("set -euxo pipefail") < script.indexOf("fallocate"))
        assertTrue(script.indexOf("swapon") < script.indexOf("freshclam --stdout"))
        assertTrue(script.indexOf("swapon") < script.indexOf("systemctl restart clamav-daemon"))
    }

    @Test
    fun swapLeavesTheHeredocsAtColumnZero() {
        for (distro in ClamAvDistro.entries) {
            val lines = userData(swapSize = 2.gibibytes, distro = distro).lines()
            for (terminator in listOf("CLAMDCONF", "RESTARTCONF")) assertTrue(terminator in lines, "$distro $terminator")
        }
    }

    @Test
    fun extraStillRunsLast() {
        for (distro in ClamAvDistro.entries) {
            val script = userData(swapSize = 2.gibibytes, extra = "echo done", distro = distro)
            assertTrue(script.endsWith("\necho done"), distro.name)
        }
    }

    @Test
    fun amazonLinuxInstallsWithDnfAndRunsClamdScan() {
        val script = userData(distro = ClamAvDistro.AmazonLinux2023)
        assertContains(script, "\n    dnf install -y clamav clamd clamav-update && break\n")
        assertContains(script, "\ntest -x /usr/sbin/clamd && test -x /usr/bin/freshclam && test -f /etc/clamd.d/scan.conf && test -f /etc/freshclam.conf || ")
        assertContains(script, "\nsed -i '/^Example/d' /etc/freshclam.conf /etc/clamd.d/scan.conf\n")
        assertContains(script, "\ncat >> /etc/clamd.d/scan.conf <<'CLAMDCONF'\nTCPSocket 3310\n")
        assertContains(script, "\ncat > /etc/systemd/system/clamd@scan.service.d/10-restart.conf <<'RESTARTCONF'\n")
        assertContains(script, "\nsystemctl enable clamd@scan\nsystemctl restart clamd@scan\n")
        for (ubuntuOnly in listOf("apt-get", "dpkg", "DEBIAN_FRONTEND", "clamav-daemon", "/etc/clamav/")) {
            assertFalse(ubuntuOnly in script, ubuntuOnly)
        }
    }

    @Test
    fun amazonLinuxFetchesDefinitionsBeforeStartingClamd() {
        val script = userData(swapSize = 2.gibibytes, distro = ClamAvDistro.AmazonLinux2023)
        assertTrue(script.indexOf("swapon") < script.indexOf("freshclam --stdout"))
        assertTrue(script.indexOf("ls /var/lib/clamav/daily.c[vl]d") < script.indexOf("systemctl restart clamd@scan"))
        assertTrue(script.indexOf("systemctl restart clamd@scan") < script.indexOf("timeout 300 bash"))
    }

    @Test
    fun amazonLinuxAmiMatchesTheArchitecture() {
        assertContains(
            emittedTerraform(ClamAvDistro.AmazonLinux2023, "amd64"),
            "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64",
        )
        assertContains(
            emittedTerraform(ClamAvDistro.AmazonLinux2023, "arm64"),
            "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64",
        )
    }

    @Test
    fun ubuntuAmiMatchesTheReleaseAndArchitecture() {
        for ((distro, release) in listOf(ClamAvDistro.Ubuntu2404 to "24.04", ClamAvDistro.Ubuntu2604 to "26.04")) {
            for (architecture in listOf("amd64", "arm64")) {
                assertContains(
                    emittedTerraform(distro, architecture),
                    "/aws/service/canonical/ubuntu/server/$release/stable/current/$architecture/hvm/ebs-gp3/ami-id",
                )
            }
        }
    }

    @Test
    fun ubuntu2604SharesTheUbuntu2404Script() {
        // Both releases install the same ClamAV build with identical units, so they share one script. This is
        // also why switching an existing host between them does not replace it (see ClamAvDistro.Ubuntu2604).
        for (swapSize in listOf(null, 2.gibibytes)) {
            assertEquals(
                userData(swapSize = swapSize, distro = ClamAvDistro.Ubuntu2404),
                userData(swapSize = swapSize, distro = ClamAvDistro.Ubuntu2604),
            )
        }
    }

    @Test
    fun unknownArchitectureIsRejected() {
        // Kernel spellings are rejected too: architecture is always Debian's spelling, whatever the distro.
        for (distro in ClamAvDistro.entries) for (architecture in listOf("x86_64", "aarch64", "")) {
            assertFailsWith<IllegalArgumentException>("$distro $architecture") {
                emitter(distro = distro, architecture = architecture)
            }
        }
    }

    @Test
    fun swapThatFitsTheRootVolumeIsEmitted() {
        val script = emit(swapSize = 16.gibibytes, rootVolumeSizeGb = 20).getValue("clamav_init.sh")
        assertContains(script, "fallocate -l 16384M /swapfile")
    }

    @Test
    fun swapThatCrowdsTheRootVolumeIsRejected() {
        assertFailsWith<IllegalArgumentException> { emit(swapSize = 17.gibibytes, rootVolumeSizeGb = 20) }
    }

    @Test
    fun swapBelowOneMebibyteIsRejected() {
        assertFailsWith<IllegalArgumentException> { emit(swapSize = DataSize(1024)) }
    }

    @Test
    fun applyWaitsForClamdThroughSsm() {
        for (distro in ClamAvDistro.entries) {
            val emitter = emitter(distro = distro)
            val instance = emitter.files.values
                .map { it.toJsonObject() }
                .firstNotNullOf { it["resource"]?.jsonObject?.get("aws_instance")?.jsonObject?.get("clamav")?.jsonObject }
            assertEquals("\${aws_iam_instance_profile.clamav.name}", instance["iam_instance_profile"]?.jsonPrimitive?.content, distro.name)
            val localExec = instance["provisioner"]!!.jsonObject["local-exec"]!!.jsonObject
            assertEquals("bash \${path.module}/clamav_ready.sh \${self.id} us-west-2", localExec["command"]!!.jsonPrimitive.content)
            assertEquals(clamAvReadyWaitScript(3310), emitter.extraFiles.getValue("clamav_ready.sh"))
        }
    }

    @Test
    fun readinessScriptEmbedsTheSsmParametersVerbatim() {
        val script = clamAvReadyWaitScript(3310)
        assertTrue(script.startsWith("#!/usr/bin/env bash\n"))
        // Quoted heredoc with the terminator at column zero, so bash passes the JSON through untouched.
        assertContains(script, "\nSSM_PARAMETERS=$(cat <<'SSMPARAMETERS'\n${clamAvReadyCheckSsmParameters(3310)}\nSSMPARAMETERS\n)\n")
    }

    @Test
    fun readinessCheckProbesTheConfiguredPort() {
        val commands = Json.parseToJsonElement(clamAvReadyCheckSsmParameters(4410)).jsonObject["commands"]!!.jsonArray
            .joinToString("\n") { it.jsonPrimitive.content }
        assertContains(commands, "/dev/tcp/127.0.0.1/4410 && printf 'nPING\\n' >&3")
        assertContains(commands, "tail -n 80 /var/log/cloud-init-output.log; exit 1")
    }

    @Test
    fun negativeSwapIsRejected() {
        assertFailsWith<IllegalArgumentException> { emit(swapSize = DataSize(-1)) }
    }
}
