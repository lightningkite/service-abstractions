package com.lightningkite.services.terraform

public class TerraformNeed<T>(
    public val name: String,
    public val cloudInfo: TerraformCloudInfo,
)