package com.lightningkite.services.terraform

public class TerraformCloudInfo(
    public val projectPrefix: String,
    public val domain: String? = null,
    public val domainZoneId: String? = null,
    public val deploymentTag: String,
    public val applicationProvider: TerraformProvider,
    public val applicationVpc: TerraformAwsVpcInfo? = null,
)

public class TerraformAwsVpcInfo(
    public val idExpression: String,
    public val securityGroupExpression: String,
    public val privateSubnetsExpression: String,
    public val natGatewayIpExpression: String,
)