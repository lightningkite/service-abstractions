package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Creates an AWS API Gateway WebSocket pub/sub infrastructure.
 *
 * This deploys a fully serverless pub/sub system using:
 * - API Gateway WebSocket API
 * - Lambda function (for connection management and message fan-out)
 * - DynamoDB table (for connection tracking)
 *
 * ## Pricing (Pay-per-use)
 *
 * - API Gateway: $1.00/million messages + $0.25/million connection-minutes
 * - Lambda: ~$0.20/million invocations
 * - DynamoDB: On-demand pricing
 * - **Idle cost: $0**
 *
 * ## Usage
 *
 * ```kotlin
 * // In your Terraform configuration
 * context(emitter: TerraformEmitterAws) {
 *     TerraformNeed<PubSub.Settings>("pubsub").awsApiGatewayWebSocket()
 * }
 *
 * // In your application
 * val pubsub = settings.pubsub("messaging", context)
 * pubsub.get<MyEvent>("events").emit(MyEvent(...))
 * ```
 *
 * @param lambdaMemoryMb Memory allocation for the Lambda function (default: 256MB)
 * @param lambdaTimeoutSeconds Timeout for Lambda invocations (default: 30s)
 * @param logRetentionDays CloudWatch log retention period (default: 14 days)
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<PubSub.Settings>.awsApiGatewayWebSocket(
    lambdaMemoryMb: Int = 256,
    lambdaTimeoutSeconds: Int = 30,
    logRetentionDays: Int = 14,
): Unit {
    if (!PubSub.Settings.supports("aws-wss")) {
        throw IllegalArgumentException("You need to reference AwsWebSocketPubSub in your server definition to use this.")
    }

    val safeName = name.replace("-", "_")

    val region = emitter.applicationRegion
    emitter.fulfillSetting(
        name,
        JsonPrimitive(value = $$"aws-wss://${aws_apigatewayv2_api.$${safeName}.id}.execute-api.$${region}.amazonaws.com/prod")
    )

    setOf(TerraformProviderImport.aws, TerraformProviderImport.archive).forEach { emitter.require(it) }

    emitter.emit(name) {
        // Lambda code inline - note: we use string concatenation to avoid Terraform interpreting JS template literals
        // The JS code uses template literals like `https://${d}/${s}` which would be interpreted as Terraform expressions
        val lambdaCode = "const{DynamoDBClient}=require(\"@aws-sdk/client-dynamodb\");" +
            "const{DynamoDBDocumentClient,PutCommand,DeleteCommand,QueryCommand}=require(\"@aws-sdk/lib-dynamodb\");" +
            "const{ApiGatewayManagementApiClient,PostToConnectionCommand}=require(\"@aws-sdk/client-apigatewaymanagementapi\");" +
            "const ddb=DynamoDBDocumentClient.from(new DynamoDBClient({})),T=process.env.TABLE_NAME;" +
            "exports.handler=async(e)=>{" +
            "const{connectionId:c,routeKey:r,domainName:d,stage:s}=e.requestContext;" +
            "if(r===\"\$connect\"){await ddb.send(new PutCommand({TableName:T,Item:{connectionId:c,channel:\"_\"}}));return{statusCode:200};}" +
            "if(r===\"\$disconnect\"){await ddb.send(new DeleteCommand({TableName:T,Key:{connectionId:c}}));return{statusCode:200};}" +
            "const b=JSON.parse(e.body||\"{}\"),api=new ApiGatewayManagementApiClient({endpoint:\"https://\"+d+\"/\"+s});" +
            "if(b.action===\"subscribe\")await ddb.send(new PutCommand({TableName:T,Item:{connectionId:c,channel:b.channel}}));" +
            "else if(b.action===\"publish\"){" +
            "const conns=(await ddb.send(new QueryCommand({TableName:T,IndexName:\"channel-index\",KeyConditionExpression:\"channel=:c\",ExpressionAttributeValues:{\":c\":b.channel}}))).Items||[];" +
            "const msg=JSON.stringify({channel:b.channel,message:b.message});" +
            "await Promise.all(conns.map(async({connectionId:x})=>{" +
            "try{await api.send(new PostToConnectionCommand({ConnectionId:x,Data:msg}));}" +
            "catch(err){if(err.statusCode===410||(err.\$metadata&&err.\$metadata.httpStatusCode===410))await ddb.send(new DeleteCommand({TableName:T,Key:{connectionId:x}}));}" +
            "}));" +
            "}" +
            "return{statusCode:200};" +
            "};"

        // DynamoDB table for connection tracking
        // Use JsonArray for attributes since Terraform expects an array of attribute objects
        val attributesArray = JsonArray(listOf(
            buildJsonObject { put("name", "connectionId"); put("type", "S") },
            buildJsonObject { put("name", "channel"); put("type", "S") }
        ))

        "resource.aws_dynamodb_table.${safeName}" {
            "name" - "${emitter.projectPrefix}-${name}-connections"
            "billing_mode" - "PAY_PER_REQUEST"
            "hash_key" - "connectionId"
            "attribute" - (attributesArray as JsonElement)

            "global_secondary_index" {
                "name" - "channel-index"
                "hash_key" - "channel"
                "projection_type" - "KEYS_ONLY"
            }

            "tags" {
                "Name" - "${emitter.projectPrefix}-${name}-connections"
            }
        }

        // IAM role for Lambda
        "resource.aws_iam_role.${safeName}" {
            "name" - "${emitter.projectPrefix}-${name}-lambda"
            "assume_role_policy" - """
{
  "Version": "2012-10-17",
  "Statement": [{
    "Action": "sts:AssumeRole",
    "Effect": "Allow",
    "Principal": { "Service": "lambda.amazonaws.com" }
  }]
}
""".trimIndent()
        }

        "resource.aws_iam_role_policy.${safeName}" {
            "name" - "${emitter.projectPrefix}-${name}-lambda"
            "role" - expression("aws_iam_role.${safeName}.id")
            "policy" - expression("""jsonencode({
  Version = "2012-10-17"
  Statement = [
    {
      Effect = "Allow"
      Action = ["dynamodb:PutItem", "dynamodb:DeleteItem", "dynamodb:Query"]
      Resource = [
        aws_dynamodb_table.${safeName}.arn,
        "${"$"}{aws_dynamodb_table.${safeName}.arn}/index/*"
      ]
    },
    {
      Effect = "Allow"
      Action = ["execute-api:ManageConnections"]
      Resource = "${"$"}{aws_apigatewayv2_api.${safeName}.execution_arn}/*"
    },
    {
      Effect = "Allow"
      Action = ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"]
      Resource = "arn:aws:logs:*:*:*"
    }
  ]
})""")
        }

        // Lambda function
        "data.archive_file.${safeName}" {
            "type" - "zip"
            "output_path" - "${"$"}{path.module}/.terraform/${safeName}_lambda.zip"
            "source" {
                "content" - lambdaCode
                "filename" - "index.js"
            }
        }

        "resource.aws_lambda_function.${safeName}" {
            "filename" - expression("data.archive_file.${safeName}.output_path")
            "function_name" - "${emitter.projectPrefix}-${name}"
            "role" - expression("aws_iam_role.${safeName}.arn")
            "handler" - "index.handler"
            "runtime" - "nodejs20.x"
            "source_code_hash" - expression("data.archive_file.${safeName}.output_base64sha256")
            "timeout" - lambdaTimeoutSeconds
            "memory_size" - lambdaMemoryMb

            "environment" {
                "variables" {
                    "TABLE_NAME" - expression("aws_dynamodb_table.${safeName}.name")
                }
            }
        }

        "resource.aws_cloudwatch_log_group.${safeName}" {
            "name" - "/aws/lambda/${emitter.projectPrefix}-${name}"
            "retention_in_days" - logRetentionDays
        }

        // API Gateway WebSocket
        "resource.aws_apigatewayv2_api.${safeName}" {
            "name" - "${emitter.projectPrefix}-${name}"
            "protocol_type" - "WEBSOCKET"
            "route_selection_expression" - "\$request.body.action"
        }

        "resource.aws_apigatewayv2_stage.${safeName}" {
            "api_id" - expression("aws_apigatewayv2_api.${safeName}.id")
            "name" - "prod"
            "auto_deploy" - true

            "default_route_settings" {
                "throttling_burst_limit" - 5000
                "throttling_rate_limit" - 10000
            }
        }

        "resource.aws_apigatewayv2_integration.${safeName}" {
            "api_id" - expression("aws_apigatewayv2_api.${safeName}.id")
            "integration_type" - "AWS_PROXY"
            "integration_uri" - expression("aws_lambda_function.${safeName}.invoke_arn")
            "integration_method" - "POST"
        }

        "resource.aws_apigatewayv2_route.${safeName}_connect" {
            "api_id" - expression("aws_apigatewayv2_api.${safeName}.id")
            "route_key" - "\$connect"
            "target" - expression("\"integrations/\${aws_apigatewayv2_integration.${safeName}.id}\"")
        }

        "resource.aws_apigatewayv2_route.${safeName}_disconnect" {
            "api_id" - expression("aws_apigatewayv2_api.${safeName}.id")
            "route_key" - "\$disconnect"
            "target" - expression("\"integrations/\${aws_apigatewayv2_integration.${safeName}.id}\"")
        }

        "resource.aws_apigatewayv2_route.${safeName}_default" {
            "api_id" - expression("aws_apigatewayv2_api.${safeName}.id")
            "route_key" - "\$default"
            "target" - expression("\"integrations/\${aws_apigatewayv2_integration.${safeName}.id}\"")
        }

        "resource.aws_lambda_permission.${safeName}" {
            "statement_id" - "AllowAPIGateway"
            "action" - "lambda:InvokeFunction"
            "function_name" - expression("aws_lambda_function.${safeName}.function_name")
            "principal" - "apigateway.amazonaws.com"
            "source_arn" - expression("\"${"$"}{aws_apigatewayv2_api.${safeName}.execution_arn}/*/*\"")
        }
    }
}
