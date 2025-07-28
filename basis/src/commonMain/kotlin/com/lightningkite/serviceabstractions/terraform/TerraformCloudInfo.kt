package com.lightningkite.serviceabstractions.terraform

public class TerraformCloudInfo(
    public val projectPrefix: String,
    public val domain: String? = null,
    public val domainZoneId: String? = null,
    public val deploymentTag: String,
    public val applicationProvider: String,
    public val applicationProviderType: String,
    public val applicationProviderRegion: String,
    public val applicationVpcIdExpression: String? = null,
    public val applicationVpcSecurityGroupExpression: String? = null,
    public val applicationVpcPrivateSubnetsExpression: String? = null,
    public val applicationVpcNatGatewayIpExpression: String? = null,
)