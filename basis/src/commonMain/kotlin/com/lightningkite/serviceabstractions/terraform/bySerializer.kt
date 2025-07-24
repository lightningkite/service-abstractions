package com.lightningkite.serviceabstractions.terraform

import kotlinx.serialization.json.JsonObject

public class TerraformNeed<T>(
    public val name: String,
    public val cloudInfo: TerraformCloudInfo,
)

public class TerraformServiceResult<T>(
    public val need: TerraformNeed<T>,
    public val terraformExpression: String,
    public val out: JsonObject
)

public class TerraformCloudInfo(
    public val projectPrefix: String,
    public val deploymentTag: String,
    public val applicationProvider: String,
    public val applicationProviderType: String,
    public val applicationVpcIdExpression: String? = null,
    public val applicationVpcSecurityGroupExpression: String? = null,
    public val applicationVpcPrivateSubnetsExpression: String? = null,
    public val applicationVpcNatGatewayIpExpression: String? = null,
)