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
import org.intellij.lang.annotations.Language

private const val DEFAULT_SECRET_LENGTH = 32

/**
 * Generates terraform import commands for recovering from state loss.
 *
 * If you see "ResourceConflictException: Function already exist" errors, it means
 * Terraform state was lost but AWS resources still exist. Run the commands returned
 * by this function to import existing resources into Terraform state.
 *
 * Usage:
 * ```kotlin
 * val commands = generatePubSubImportCommands("LightningServerDemo", "pubSub", "us-west-2", "123456789012")
 * commands.forEach { println(it) }
 * // Then run the printed commands in the terraform directory
 * ```
 *
 * @param projectPrefix The project prefix used when creating resources
 * @param name The name used for the PubSub setting
 * @param region The AWS region where resources were created
 * @param accountId Your AWS account ID (12-digit number)
 * @return List of terraform import commands to run
 */
public fun generatePubSubImportCommands(
    projectPrefix: String,
    name: String,
    region: String,
    accountId: String
): List<String> {
    val safeName = name.replace("-", "_")
    val functionName = "$projectPrefix-$name"
    val tableName = "$projectPrefix-$name-connections"
    val roleName = "$projectPrefix-$name-lambda"
    val apiName = "$projectPrefix-$name"

    return listOf(
        "# Import commands for $projectPrefix PubSub infrastructure",
        "# Run these from the terraform directory after 'terraform init'",
        "",
        "# DynamoDB table",
        "terraform import 'aws_dynamodb_table.$safeName' '$tableName'",
        "",
        "# IAM role and policy",
        "terraform import 'aws_iam_role.$safeName' '$roleName'",
        "terraform import 'aws_iam_role_policy.$safeName' '$roleName:$roleName'",
        "",
        "# CloudWatch log group",
        "terraform import 'aws_cloudwatch_log_group.$safeName' '/aws/lambda/$functionName'",
        "",
        "# Lambda function",
        "terraform import 'aws_lambda_function.$safeName' '$functionName'",
        "",
        "# API Gateway (get the API ID from AWS Console or CLI first)",
        "# aws apigatewayv2 get-apis --query \"Items[?Name=='$apiName'].ApiId\" --output text",
        "# Then run:",
        "# terraform import 'aws_apigatewayv2_api.$safeName' '<API_ID>'",
        "# terraform import 'aws_apigatewayv2_stage.$safeName' '<API_ID>/prod'",
        "# terraform import 'aws_apigatewayv2_integration.$safeName' '<API_ID>/<INTEGRATION_ID>'",
        "# terraform import 'aws_apigatewayv2_route.${safeName}_connect' '<API_ID>/<ROUTE_ID>'",
        "# terraform import 'aws_apigatewayv2_route.${safeName}_disconnect' '<API_ID>/<ROUTE_ID>'",
        "# terraform import 'aws_apigatewayv2_route.${safeName}_default' '<API_ID>/<ROUTE_ID>'",
        "",
        "# Lambda permission",
        "terraform import 'aws_lambda_permission.$safeName' '$functionName/AllowAPIGateway'",
        "",
        "# Random password (cannot be imported - will be recreated)",
        "# After import, run 'terraform apply' to sync state",
    )
}

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
 * @param secretLength Length of the randomly generated secret for authentication (default: 32)
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<PubSub.Settings>.awsApiGatewayWebSocket(
    lambdaMemoryMb: Int = 256,
    lambdaTimeoutSeconds: Int = 30,
    logRetentionDays: Int = 14,
    secretLength: Int = DEFAULT_SECRET_LENGTH,
): Unit {
    if (!PubSub.Settings.supports("aws-wss")) {
        throw IllegalArgumentException("You need to reference AwsWebSocketPubSub in your server definition to use this.")
    }

    val safeName = name.replace("-", "_")

    val region = emitter.applicationRegion

    // Fulfill setting with URL that includes the secret and a "ready" marker
    // The ready marker references a local value that depends on all infrastructure
    // This creates an implicit dependency chain: main Lambda -> local -> all pubsub resources
    // The secret is required for authentication on connect
    emitter.fulfillSetting(
        name,
        JsonPrimitive(value = $$"aws-wss://${aws_apigatewayv2_api.$${safeName}.id}.execute-api.$${region}.amazonaws.com/prod?secret=${random_password.$${safeName}_secret.result}&ready=${local.$${safeName}_ready}")
    )

    setOf(TerraformProviderImport.aws, TerraformProviderImport.archive, TerraformProviderImport.random).forEach { emitter.require(it) }

    emitter.emit(name) {
        // Random secret for authentication
        "resource.random_password.${safeName}_secret" {
            "length" - secretLength
            "special" - false  // URL-safe characters only
        }

        // Local value that depends on all infrastructure being ready
        // This allows other resources to depend on the entire PubSub stack via this local
        "locals" {
            "${safeName}_ready" - expression("""length([
                aws_apigatewayv2_api.${safeName}.id,
                aws_apigatewayv2_stage.${safeName}.id,
                aws_apigatewayv2_integration.${safeName}.id,
                aws_apigatewayv2_route.${safeName}_connect.id,
                aws_apigatewayv2_route.${safeName}_disconnect.id,
                aws_apigatewayv2_route.${safeName}_default.id,
                aws_lambda_function.${safeName}.arn,
                aws_lambda_permission.${safeName}.id,
                random_password.${safeName}_secret.result
            ])""")
        }

        // Lambda code inline - note: we use string concatenation to avoid Terraform interpreting JS template literals
        // The JS code uses template literals like `https://${d}/${s}` which would be interpreted as Terraform expressions
        @Language("JavaScript") val lambdaCode =
            $$"""
            const {DynamoDBClient} = require("@aws-sdk/client-dynamodb");
            const {DynamoDBDocumentClient, PutCommand, DeleteCommand, QueryCommand, ScanCommand} = require("@aws-sdk/lib-dynamodb");
            const {ApiGatewayManagementApiClient, PostToConnectionCommand} = require("@aws-sdk/client-apigatewaymanagementapi");
            const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({})), T = process.env.TABLE_NAME, SECRET = process.env.SECRET;
            exports.handler = async (e) => {
                const {connectionId: c, routeKey: r, domainName: d, stage: s} = e.requestContext;
                //console.log('Route:', r, 'ConnId:', c, 'Body:', e.body);
                if (r === "$connect") {
                    const qs = e.queryStringParameters || {};
                    if (qs.secret !== SECRET) {
                        //console.log('Connect DENIED: invalid secret');
                        return {statusCode: 403, body: "Forbidden: invalid secret"};
                    }
                    //console.log('Connect OK');
                    return {statusCode: 200};
                }
                if (r === "$disconnect") {
                    const subs = (await ddb.send(new QueryCommand({
                        TableName: T,
                        KeyConditionExpression: "connectionId=:c",
                        ExpressionAttributeValues: {":c": c}
                    }))).Items || [];
                    //console.log('Disconnect, cleaning', subs.length, 'subs');
                    await Promise.all(subs.map(s => ddb.send(new DeleteCommand({
                        TableName: T,
                        Key: {connectionId: c, channel: s.channel}
                    }))));
                    return {statusCode: 200};
                }
                const b = JSON.parse(e.body || "{}"), api = new ApiGatewayManagementApiClient({endpoint: "https://" + d + "/" + s});
                //console.log('Action:', b.action, 'Channel:', b.channel);
                if (b.action === "subscribe") {
                    await ddb.send(new PutCommand({TableName: T, Item: {connectionId: c, channel: b.channel}}));
                    //console.log('Subscribed', c, 'to', b.channel);
                } else if (b.action === "publish") {
                    const conns = (await ddb.send(new QueryCommand({
                        TableName: T,
                        IndexName: "channel-index",
                        KeyConditionExpression: "channel=:c",
                        ExpressionAttributeValues: {":c": b.channel}
                    }))).Items || [];
                    //console.log('Publishing to', conns.length, 'connections on', b.channel);
                    const msg = JSON.stringify({channel: b.channel, message: b.message});
                    await Promise.all(conns.map(async ({connectionId: x, channel: ch}) => {
                        try {
                            await api.send(new PostToConnectionCommand({ConnectionId: x, Data: msg}));
                            //console.log('Sent to', x);
                        } catch (err) {
                            //console.log('Failed to send to', x, ':', err.message);
                            if (err.statusCode === 410 || (err.$metadata && err.$metadata.httpStatusCode === 410)) await ddb.send(new DeleteCommand({
                                TableName: T,
                                Key: {connectionId: x, channel: ch}
                            }));
                        }
                    }));
                }
                return {statusCode: 200};
            };
            """.trimIndent()

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
            "range_key" - "channel"  // Composite key: (connectionId, channel)
            "attribute" - (attributesArray as JsonElement)

            "global_secondary_index" {
                "name" - "channel-index"
                "hash_key" - "channel"
                "projection_type" - "ALL"  // Need ALL to get connectionId for cleanup
            }

            "tags" {
                "Name" - "${emitter.projectPrefix}-${name}-connections"
            }

            "lifecycle" {
                "create_before_destroy" - false
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

            "lifecycle" {
                "create_before_destroy" - false
            }
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

        // CloudWatch log group - create BEFORE Lambda to prevent AWS auto-creating it
        // This ensures Terraform manages the log group and avoids conflicts
        "resource.aws_cloudwatch_log_group.${safeName}" {
            "name" - "/aws/lambda/${emitter.projectPrefix}-${name}"
            "retention_in_days" - logRetentionDays
        }

        "resource.aws_lambda_function.${safeName}" {
            // Explicit dependency ensures log group exists before Lambda runs
            "depends_on" - listOf("aws_cloudwatch_log_group.${safeName}")
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
                    "SECRET" - expression("random_password.${safeName}_secret.result")
                }
            }

            // Lifecycle rules to handle state/resource mismatches
            // create_before_destroy = false ensures old resource is destroyed before new one is created
            // This prevents "already exists" conflicts when resource names haven't changed
            "lifecycle" {
                "create_before_destroy" - false
            }
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
