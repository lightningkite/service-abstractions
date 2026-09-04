package com.lightningkite.services.files.clamav

import com.lightningkite.services.data.DataSize
import com.lightningkite.services.data.DataSize.Companion.mebibytes
import com.lightningkite.services.files.FileScanner
import com.lightningkite.services.terraform.*
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A reachable clamd daemon, ready to be handed to one or more [TerraformNeed]s for a [FileScanner].
 *
 * Produced by [awsEc2ClamAv]. Kept separate from the need it fulfills so that a single ClamAV host can
 * back several scanner settings (the same way [com.lightningkite.services.cache.redis] shares one cache).
 *
 * @property hostExpression A terraform expression evaluating to the host clamd listens on.
 * @property port The TCP port clamd listens on.
 * @property scanTimeout How long the client will wait on a single scan before failing closed.
 */
public class ReusableClamAvSetting(
    public val hostExpression: String,
    public val port: Int,
    public val scanTimeout: Duration,
) {
    /** The `clamav://` settings URL pointing at this daemon. */
    public val settingUrl: String
        get() = "clamav://$hostExpression:$port/UNIX?timeoutSeconds=${scanTimeout.inWholeSeconds}"
}

/**
 * Creates a single EC2 instance running the ClamAV daemon (clamd), reachable only from inside the VPC.
 *
 * The instance is placed in a **private subnet** and given no public IP address: the only way to reach
 * clamd is from within the VPC, which is exactly what the application (lambda or otherwise) needs.
 *
 * Emits terraform resources for:
 * - `data.aws_ssm_parameter`: the current Canonical Ubuntu AMI id (skipped when [amiId] is given)
 * - `aws_security_group` + ingress/egress rules: clamd's [port] reachable only from the VPC's own
 *   security group
 * - `aws_network_interface`: a dedicated ENI, so the private IP baked into the settings URL survives
 *   instance replacement (a new AMI or a changed [userDataExtra] replaces the instance, not the ENI)
 * - `aws_iam_role` / `aws_iam_role_policy_attachment` / `aws_iam_instance_profile`: SSM Session Manager
 *   access for operators (see [enableSessionManager]) — there is no SSH key and no public IP
 * - `aws_instance`: the host itself, whose user data installs clamav, pulls virus definitions, and
 *   configures clamd to listen on TCP
 *
 * ## Requirements
 *
 * - **A VPC**: `emitter.applicationVpc` must be an [AwsVpc.VpcInfo]. There is no meaningful "private
 *   subnet only" deployment without one, so this throws rather than silently falling back.
 * - **Outbound internet access**: the private subnet needs a NAT gateway. `freshclam` downloads virus
 *   definitions over HTTPS on first boot and hourly thereafter, and Session Manager also needs egress.
 *   A subnet with no route out will produce an instance where clamd never starts (see below).
 *
 * ## Sizing
 *
 * clamd holds the whole signature database in memory — roughly 1.5 GB and growing. [instanceType]
 * defaults to `t3.medium` (4 GB) for that reason; `t3.small` (2 GB) is the practical floor and will start
 * failing as the database grows. `ConcurrentDatabaseReload` is disabled in the generated config because
 * reloading concurrently holds two copies of the database in memory, which is what usually OOMs a small
 * host.
 *
 * ## Gotchas
 *
 * - **clamd will not start without definitions**: Ubuntu's `clamav-daemon.service` carries two
 *   `ConditionPathExistsGlob` lines — `/var/lib/clamav/main.{c[vl]d,inc}` and
 *   `/var/lib/clamav/daily.{c[vl]d,inc}` — and *both* must be satisfied, so it silently refuses to start
 *   until freshclam has completed a download. The generated user data runs freshclam (with retries)
 *   *before* enabling the daemon for this reason. If clamd is unreachable after an apply, read
 *   `/var/log/cloud-init-output.log` on the instance first.
 * - **Socket activation does not cover the TCP listener**: the packaged unit has
 *   `Requires=clamav-daemon.socket`, but that socket only holds the unix socket
 *   (`/run/clamav/clamd.ctl`) — its TCP `ListenStream` is commented out. clamd opens [port] itself from
 *   `clamd.conf`, so if clamd dies nothing reactivates it for a TCP client; the connection is simply
 *   refused. That is why the generated user data installs an explicit `Restart=always` drop-in.
 * - **First boot takes a few minutes**: terraform reports the instance as created as soon as it is
 *   running, well before the definition download finishes. Expect the scanner's health check to report
 *   ERROR for a short while after an apply.
 * - **Single instance, no failover**: this is one EC2 instance. It is a single point of failure, and
 *   [FileScanner] fails closed, so uploads will be rejected while it is down. If that is unacceptable,
 *   put a network load balancer in front of an autoscaling group instead.
 *
 * @param name The base name for the generated terraform resources.
 * @param instanceType The EC2 instance type. Must match [architecture] (`t3.*` for amd64, `t4g.*` for arm64).
 * @param architecture CPU architecture for the SSM AMI lookup; must match [instanceType].
 * @param port The TCP port clamd listens on. 3310 is the ClamAV default.
 * @param scanTimeout Baked into the settings URL; how long the client waits on a scan before failing closed.
 * @param subnetIdExpression Which subnet to place the ENI in. Defaults to the first private subnet.
 * @param maxFileSize Largest single file clamd will accept over the stream socket
 *   (`StreamMaxLength`/`MaxFileSize`). Files above this are rejected, not silently passed.
 * @param maxScanSize Total data clamd will read out of one submission (`MaxScanSize`); this is the
 *   archive-expansion ceiling, so it should equal or exceed [maxFileSize].
 * @param maxThreads Concurrent scans clamd will run. Each in-flight scan costs memory on top of the database.
 * @param rootVolumeSizeGb Root EBS volume size. The signature database alone is well over a gigabyte.
 * @param enableSessionManager Attach an instance profile granting `AmazonSSMManagedInstanceCore` so
 *   operators can `aws ssm start-session` into the host. There is no other way in — no public IP, no key pair.
 * @param amiId Pin a specific AMI instead of resolving the current Ubuntu image. Note that the generated
 *   user data is Debian/Ubuntu specific (`apt-get`, `/etc/clamav/clamd.conf`).
 * @param ubuntuVersion Ubuntu release to resolve via SSM when [amiId] is not given.
 * @param kmsKey Key for the encrypted root volume. Defaults to the deployment-wide `encryptionKey`.
 * @param userDataExtra Shell appended to the generated user data, after clamd is up. Changing it replaces
 *   the instance.
 * @throws IllegalArgumentException if the emitter has no VPC, or if `ClamAvFileScanner` is not referenced.
 */
context(emitter: TerraformEmitterAws)
public fun awsEc2ClamAv(
    name: String,
    instanceType: String = "t3.medium",
    architecture: String = "amd64",
    port: Int = 3310,
    scanTimeout: Duration = 30.seconds,
    subnetIdExpression: String? = null,
    maxFileSize: DataSize = 100.mebibytes,
    maxScanSize: DataSize = 400.mebibytes,
    maxThreads: Int = 12,
    rootVolumeSizeGb: Int = 20,
    enableSessionManager: Boolean = true,
    amiId: String? = null,
    ubuntuVersion: String = "24.04",
    kmsKey: KmsKeySource? = null,
    userDataExtra: String = "",
): ReusableClamAvSetting {
    if (!FileScanner.Settings.supports("clamav")) {
        throw IllegalArgumentException("You need to reference ClamAvFileScanner in your server definition to use this.")
    }
    val vpcInfo = emitter.applicationVpc as? AwsVpc.VpcInfo
        ?: throw IllegalArgumentException(
            "awsEc2ClamAv places the daemon in a private subnet, so it requires a VPC. " +
                    "Set the emitter's applicationVpc to an AwsVpc.VpcInfo."
        )
    require(maxScanSize >= maxFileSize) {
        "maxScanSizeMegabytes ($maxScanSize) is the ceiling on everything clamd reads out of one " +
                "submission, so it cannot be below maxFileSizeMegabytes ($maxFileSize)."
    }

    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }
    val kmsKeyArn = (kmsKey ?: emitter.encryptionKey).resolveKeyArn(name)

    emitter.emit(name) {
        // Pick one private subnet for the ENI. privateSubnets is a terraform *expression* for a list, and
        // an expression cannot be nested inside another one, so it is bounced through a local value first.
        val subnetId = subnetIdExpression ?: run {
            "locals.${name}_private_subnets" - vpcInfo.privateSubnets
            expression("local.${name}_private_subnets[0]")
        }

        val ami = amiId?.let { JsonPrimitive(it) } ?: run {
            "data.aws_ssm_parameter.${name}_ami" {
                "name" - "/aws/service/canonical/ubuntu/server/$ubuntuVersion/stable/current/$architecture/hvm/ebs-gp3/ami-id"
            }
            JsonPrimitive(expression("data.aws_ssm_parameter.${name}_ami.value"))
        }

        "resource.aws_security_group.${name}" {
            "name" - "${emitter.projectPrefix}-${name}-clamav"
            "description" - "ClamAV daemon; reachable only from within the VPC"
            "vpc_id" - vpcInfo.id
            "tags" {
                "Name" - "${emitter.projectPrefix}-${name}-clamav"
            }
        }
        "resource.aws_vpc_security_group_ingress_rule.${name}" {
            "security_group_id" - expression("aws_security_group.${name}.id")
            "referenced_security_group_id" - vpcInfo.securityGroup
            "description" - "clamd scan requests"
            "from_port" - port
            "to_port" - port
            "ip_protocol" - "tcp"
        }
        // Outbound is deliberately unrestricted: freshclam pulls definitions from the ClamAV CDN over
        // HTTPS (via the subnet's NAT gateway) and Session Manager needs to reach the SSM endpoints.
        // Nothing can reach *in* except the ingress rules above.
        "resource.aws_vpc_security_group_egress_rule.${name}_all" {
            "security_group_id" - expression("aws_security_group.${name}.id")
            "description" - "freshclam definition updates and SSM"
            "cidr_ipv4" - "0.0.0.0/0"
            "ip_protocol" - "-1"
        }
        "resource.aws_vpc_security_group_egress_rule.${name}_v6_all" {
            "security_group_id" - expression("aws_security_group.${name}.id")
            "description" - "freshclam definition updates and SSM"
            "cidr_ipv6" - "::/0"
            "ip_protocol" - "-1"
        }

        // A standalone ENI rather than an instance-managed one: the settings URL below embeds the private
        // IP, and this keeps that IP (and therefore the deployed settings) stable when the instance is
        // replaced. It also structurally guarantees no public IP is ever attached.
        "resource.aws_network_interface.${name}" {
            "subnet_id" - subnetId
            "security_groups" - listOf(
                expression("aws_security_group.${name}.id"),
            )
            "tags" {
                "Name" - "${emitter.projectPrefix}-${name}-clamav"
            }
        }

        val instanceProfile = if (enableSessionManager) {
            "resource.aws_iam_role.${name}" {
                "name" - "${emitter.projectPrefix}-${name}-clamav"
                "assume_role_policy" - $$"""
                    {
                        "Version": "2012-10-17",
                        "Statement": [
                            {
                                "Action": "sts:AssumeRole",
                                "Effect": "Allow",
                                "Principal": { "Service": "ec2.amazonaws.com" }
                            }
                        ]
                    }
                """.trimIndent()
            }
            "resource.aws_iam_role_policy_attachment.${name}_ssm" {
                "role" - expression("aws_iam_role.${name}.name")
                "policy_arn" - "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
            }
            "resource.aws_iam_instance_profile.${name}" {
                "name" - "${emitter.projectPrefix}-${name}-clamav"
                "role" - expression("aws_iam_role.${name}.name")
            }
            expression("aws_iam_instance_profile.${name}.name")
        } else null

        "resource.aws_instance.${name}" {
            "ami" - ami
            "instance_type" - instanceType
            instanceProfile?.let { "iam_instance_profile" - it }
            "network_interface" {
                "network_interface_id" - expression("aws_network_interface.${name}.id")
                "device_index" - 0
            }
            "root_block_device" {
                "volume_size" - rootVolumeSizeGb
                "volume_type" - "gp3"
                "encrypted" - true
                if (kmsKeyArn != null) "kms_key_id" - kmsKeyArn
            }
            "metadata_options" {
                "http_endpoint" - "enabled"
                "http_tokens" - "required"
            }
            emitter.emitExtra(
                "${name}_init.sh", clamAvUserData(
                    port = port,
                    maxFileSize = maxFileSize,
                    maxScanSize = maxScanSize,
                    maxThreads = maxThreads,
                    extra = userDataExtra,
                )
            )
            "user_data" - expression("local.${name}_init")
            // The daemon is configured entirely from user data, so a config change has to rebuild the host.
            "user_data_replace_on_change" - true

            "lifecycle" {
                "ignore_changes" - listOf("ami")
            }
            "tags" {
                "Name" - "${emitter.projectPrefix}-${name}-clamav"
            }
        }

        "locals.${name}_init" - $$"""${file("${path.module}/$${name}_init.sh")}"""
    }

    return ReusableClamAvSetting(
        hostExpression = TerraformJsonObject.expression("aws_network_interface.${name}.private_ip"),
        port = port,
        scanTimeout = scanTimeout,
    )
}

/**
 * Points this [FileScanner] need at an already-created ClamAV daemon, so several scanners can share one host.
 *
 * ```kotlin
 * context(emitter) {
 *     val clamav = awsEc2ClamAv("clamav")
 *     need<FileScanner.Settings>("uploadScanner").clamav(clamav)
 *     need<FileScanner.Settings>("avatarScanner").clamav(clamav)
 * }
 * ```
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<FileScanner.Settings>.clamav(
    reusableClamAvSetting: ReusableClamAvSetting,
): ReusableClamAvSetting {
    if (!FileScanner.Settings.supports("clamav")) {
        throw IllegalArgumentException("You need to reference ClamAvFileScanner in your server definition to use this.")
    }
    emitter.fulfillSetting(name, JsonPrimitive(reusableClamAvSetting.settingUrl))
    return reusableClamAvSetting
}

/**
 * Creates an EC2 instance running clamd in the VPC's private subnet and fulfills this need with a
 * `clamav://` URL pointing at it.
 *
 * ```kotlin
 * context(emitter) {
 *     need<FileScanner.Settings>("scanner").awsEc2ClamAv()
 * }
 * ```
 *
 * See [awsEc2ClamAv] for what is emitted, what it requires of the VPC, and how to size it.
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<FileScanner.Settings>.awsEc2ClamAv(
    instanceType: String = "t3.medium",
    architecture: String = "amd64",
    port: Int = 3310,
    scanTimeout: Duration = 30.seconds,
    subnetIdExpression: String? = null,
    maxFileSize: DataSize = 100.mebibytes,
    maxScanSize: DataSize = 400.mebibytes,
    maxThreads: Int = 12,
    rootVolumeSizeGb: Int = 20,
    enableSessionManager: Boolean = true,
    amiId: String? = null,
    ubuntuVersion: String = "24.04",
    kmsKey: KmsKeySource? = null,
    userDataExtra: String = "",
): ReusableClamAvSetting = clamav(
    awsEc2ClamAv(
        name = name,
        instanceType = instanceType,
        architecture = architecture,
        port = port,
        scanTimeout = scanTimeout,
        subnetIdExpression = subnetIdExpression,
        maxFileSize = maxFileSize,
        maxScanSize = maxScanSize,
        maxThreads = maxThreads,
        rootVolumeSizeGb = rootVolumeSizeGb,
        enableSessionManager = enableSessionManager,
        amiId = amiId,
        ubuntuVersion = ubuntuVersion,
        kmsKey = kmsKey,
        userDataExtra = userDataExtra,
    )
)

/**
 * Cloud-init shell that turns a stock Ubuntu image into a clamd host listening on [port].
 *
 * Deliberately written without shell variables so the whole thing can be a plain Kotlin template; the
 * heredocs are quoted so clamd's config is written literally.
 *
 * Every retry loop is followed by an assertion on what it was supposed to achieve, and the script ends by
 * probing [port] itself. The retries give up silently, so without those checks a host that failed to
 * install or failed to fetch definitions still runs the rest of the script and terraform still reports a
 * successful apply — the breakage would only show up later as uploads failing closed.
 */
private fun clamAvUserData(
    port: Int,
    maxFileSize: DataSize,
    maxScanSize: DataSize,
    maxThreads: Int,
    extra: String,
): String {
    // `extra` is appended rather than interpolated on purpose: trimIndent() runs *after* interpolation, so
    // a multi-line caller script at column zero would drop the common indent to zero and leave the rest of
    // this template — including the heredoc terminator, which must start at column zero — indented.
    val script = """
    #!/bin/bash
    set -euxo pipefail
    export DEBIAN_FRONTEND=noninteractive

    # cloud-init can win the race against unattended-upgrades for the dpkg lock; retry rather than
    # leaving a host with no scanner on it.
    for attempt in 1 2 3 4 5; do
        apt-get update -y && break
        sleep 15
    done
    for attempt in 1 2 3 4 5; do
        apt-get install -y clamav clamav-daemon && break
        sleep 15
    done
    # The retry loop above gives up silently; without this the failure surfaces several commands later as
    # a confusing systemctl error. Assert the postcondition so cloud-init-output.log names the real cause.
    dpkg -s clamav-daemon >/dev/null 2>&1 || { echo "FATAL: clamav-daemon failed to install after 5 attempts" >&2; exit 1; }

    # clamav-daemon.service carries ConditionPathExistsGlob on both /var/lib/clamav/main.{c[vl]d,inc} and
    # /var/lib/clamav/daily.{c[vl]d,inc}, so it will refuse to start until definitions exist. The packaged
    # freshclam service holds the database lock, so stop it for the initial (foreground) download and hand
    # the job back afterwards.
    systemctl stop clamav-freshclam || true
    for attempt in 1 2 3 4 5; do
        freshclam --stdout && break
        sleep 30
    done
    # Assert exactly what clamav-daemon.service conditions on: *both* main and daily, not just one of them.
    # A run that fetched main and then failed on daily leaves clamd permanently unable to start, and
    # checking only for "some definition file" would let that through to be misreported later as clamd
    # failing to answer on the port.
    ls /var/lib/clamav/main.c[vl]d >/dev/null 2>&1 || { echo "FATAL: freshclam produced no main virus definitions after 5 attempts" >&2; exit 1; }
    ls /var/lib/clamav/daily.c[vl]d >/dev/null 2>&1 || { echo "FATAL: freshclam produced no daily virus definitions after 5 attempts" >&2; exit 1; }
    systemctl enable clamav-freshclam
    systemctl start clamav-freshclam

    # Out of the box clamd only listens on a unix socket; add the TCP listener the client connects to.
    # Existing directives are stripped first because clamd takes the *first* occurrence of a key.
    sed -i '/^TCPSocket/d; /^TCPAddr/d; /^StreamMaxLength/d; /^MaxFileSize/d; /^MaxScanSize/d; /^MaxThreads/d; /^ConcurrentDatabaseReload/d' /etc/clamav/clamd.conf
    cat >> /etc/clamav/clamd.conf <<'CLAMDCONF'
    TCPSocket $port
    TCPAddr 0.0.0.0
    StreamMaxLength ${maxFileSize.inWholeMebibytes}M
    MaxFileSize ${maxFileSize.inWholeMebibytes}M
    MaxScanSize ${maxScanSize.inWholeMebibytes}M
    MaxThreads $maxThreads
    ConcurrentDatabaseReload no
    CLAMDCONF

    # The packaged clamav-daemon.service sets no Restart= at all, so an OOM kill or a crash leaves the host
    # up with no scanner and every upload failing closed until an operator intervenes. Requires= on
    # clamav-daemon.socket does not help: that socket only holds the unix socket, so a TCP client just gets
    # connection refused with nothing to trigger reactivation. A drop-in survives package upgrades; editing
    # the packaged unit would not. RestartSec is 10s rather than the 100ms default so that a host which is
    # OOMing on database load backs off instead of burning through systemd's start limit and giving up
    # permanently.
    mkdir -p /etc/systemd/system/clamav-daemon.service.d
    cat > /etc/systemd/system/clamav-daemon.service.d/10-restart.conf <<'RESTARTCONF'
    [Service]
    Restart=always
    RestartSec=10
    RESTARTCONF
    systemctl daemon-reload

    systemctl enable clamav-daemon
    systemctl restart clamav-daemon

    # `systemctl restart` returning 0 only means the unit was started, not that clamd is accepting
    # connections on the TCP port the client actually uses. Probe it so that a script which exits 0 means
    # a working scanner. Kept free of shell variables like the rest of this template.
    timeout 300 bash -c 'until (exec 3<>/dev/tcp/127.0.0.1/$port && printf "nPING\n" >&3 && head -c 4 <&3 | grep -q PONG) 2>/dev/null; do sleep 5; done' || { echo "FATAL: clamd is not answering on port $port five minutes after start" >&2; exit 1; }
    echo "clamd is answering on port $port"
    """.trimIndent()
    return if (extra.isBlank()) script else script + "\n" + extra.trimIndent()
}
