package com.lightningkite.services.terraform

/**
 * How an AWS resource that supports encryption at rest should obtain its KMS key.
 *
 * Modelled after the VPC choice ([AwsVpc]): a deployment can opt out, let terraform create and own a
 * dedicated key, or reference a key managed elsewhere (a shared deployment key or a central/external key).
 *
 * Note that for some resources (notably DocumentDB/RDS) the key is fixed at creation — changing it forces
 * the resource to be replaced. Such generators take this as a *required* argument so the choice is always
 * a deliberate, per-resource decision rather than something a shared default can silently change.
 */
public sealed interface KmsKeySource {
    /** Use AWS-managed/default encryption — no customer-managed key. The backwards-compatible default. */
    public data object AwsManaged : KmsKeySource

    /**
     * Terraform creates and owns a dedicated customer-managed key for this resource, with automatic key
     * rotation enabled. Automatic rotation rotates the key *material* without changing the key ARN, so it is
     * safe even for resources whose key is immutable at creation.
     */
    public data object CreateDedicated : KmsKeySource

    /** Reference an existing key by its ARN expression — a shared deployment key or an external/central key. */
    public data class Existing(public val keyArnExpression: String) : KmsKeySource
}

/**
 * Resolve this key source to a KMS key ARN expression for use as a `kms_key_id`, emitting a dedicated key
 * (named after [name]) when [KmsKeySource.CreateDedicated] is chosen. Returns `null` for
 * [KmsKeySource.AwsManaged], in which case the caller should omit any key reference and let AWS use its
 * default/managed encryption.
 *
 * The dedicated key is created with the AWS default key policy (account-root full access), which is
 * sufficient for services that create grants on your behalf (DocumentDB/RDS, S3, SSM, SNS). Resources whose
 * key needs an explicit service-principal grant (e.g. CloudWatch Logs, or EBS used by an Auto Scaling group)
 * should instead be handed a [KmsKeySource.Existing] key whose policy the caller controls.
 */
context(emitter: TerraformEmitterAws)
public fun KmsKeySource.resolveKeyArn(name: String): String? = when (this) {
    KmsKeySource.AwsManaged -> null
    is KmsKeySource.Existing -> keyArnExpression
    KmsKeySource.CreateDedicated -> {
        emitter.emit(name) {
            "resource.aws_kms_key.${name}_cmk" {
                "description" - "Customer-managed key for ${emitter.projectPrefix}-$name"
                "enable_key_rotation" - true
            }
            "resource.aws_kms_alias.${name}_cmk" {
                "name" - "alias/${emitter.projectPrefix}-$name"
                "target_key_id" - expression("aws_kms_key.${name}_cmk.id")
            }
        }
        TerraformJsonObject.expression("aws_kms_key.${name}_cmk.arn")
    }
}
