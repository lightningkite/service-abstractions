################################################################################
# AWS WebSocket PubSub - Terraform Configuration
#
# This Terraform module deploys a fully serverless pub/sub system using:
# - API Gateway WebSocket API
# - Lambda (single function for all routes)
# - DynamoDB (connection tracking)
#
# Usage:
#   module "pubsub" {
#     source = "./path/to/pubsub-aws/terraform"
#     prefix = "myapp"
#   }
#
# After deployment, use the websocket_url output in your Kotlin code:
#   PubSub.Settings("aws-wss://${module.pubsub.websocket_url}")
#
################################################################################

terraform {
  required_version = ">= 1.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 4.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = ">= 2.0"
    }
  }
}

variable "prefix" {
  description = "Resource name prefix"
  type        = string
}

variable "tags" {
  description = "Tags to apply to all resources"
  type        = map(string)
  default     = {}
}

locals {
  lambda_code = <<-EOF
const{DynamoDBClient}=require("@aws-sdk/client-dynamodb");
const{DynamoDBDocumentClient,PutCommand,DeleteCommand,QueryCommand}=require("@aws-sdk/lib-dynamodb");
const{ApiGatewayManagementApiClient,PostToConnectionCommand}=require("@aws-sdk/client-apigatewaymanagementapi");
const ddb=DynamoDBDocumentClient.from(new DynamoDBClient({})),T=process.env.TABLE_NAME;

exports.handler=async(e)=>{
  const{connectionId:c,routeKey:r,domainName:d,stage:s}=e.requestContext;
  if(r==="$connect"){await ddb.send(new PutCommand({TableName:T,Item:{connectionId:c,channel:"_"}}));return{statusCode:200};}
  if(r==="$disconnect"){await ddb.send(new DeleteCommand({TableName:T,Key:{connectionId:c}}));return{statusCode:200};}
  const b=JSON.parse(e.body||"{}"),api=new ApiGatewayManagementApiClient({endpoint:`https://$${d}/$${s}`});
  if(b.action==="subscribe")await ddb.send(new PutCommand({TableName:T,Item:{connectionId:c,channel:b.channel}}));
  else if(b.action==="publish"){
    const conns=(await ddb.send(new QueryCommand({TableName:T,IndexName:"channel-index",KeyConditionExpression:"channel=:c",ExpressionAttributeValues:{":c":b.channel}}))).Items||[];
    const msg=JSON.stringify({channel:b.channel,message:b.message});
    await Promise.all(conns.map(async({connectionId:x})=>{
      try{await api.send(new PostToConnectionCommand({ConnectionId:x,Data:msg}));}
      catch(err){if(err.statusCode===410||err.$metadata?.httpStatusCode===410)await ddb.send(new DeleteCommand({TableName:T,Key:{connectionId:x}}));}
    }));
  }
  return{statusCode:200};
};
EOF

  default_tags = merge(var.tags, {
    Service = "pubsub"
    Module  = "pubsub-aws"
  })
}

################################################################################
# DynamoDB Table - Connection Tracking
################################################################################

resource "aws_dynamodb_table" "connections" {
  name         = "${var.prefix}-pubsub-connections"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "connectionId"

  attribute {
    name = "connectionId"
    type = "S"
  }

  attribute {
    name = "channel"
    type = "S"
  }

  global_secondary_index {
    name            = "channel-index"
    hash_key        = "channel"
    projection_type = "KEYS_ONLY"
  }

  tags = local.default_tags
}

################################################################################
# IAM Role - Lambda Execution
################################################################################

resource "aws_iam_role" "lambda" {
  name = "${var.prefix}-pubsub-lambda"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "lambda.amazonaws.com"
      }
    }]
  })

  tags = local.default_tags
}

resource "aws_iam_role_policy" "lambda" {
  name = "${var.prefix}-pubsub-lambda"
  role = aws_iam_role.lambda.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "dynamodb:PutItem",
          "dynamodb:DeleteItem",
          "dynamodb:Query"
        ]
        Resource = [
          aws_dynamodb_table.connections.arn,
          "${aws_dynamodb_table.connections.arn}/index/*"
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["execute-api:ManageConnections"]
        Resource = "${aws_apigatewayv2_api.websocket.execution_arn}/*"
      },
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "arn:aws:logs:*:*:*"
      }
    ]
  })
}

################################################################################
# Lambda Function
################################################################################

data "archive_file" "lambda_zip" {
  type        = "zip"
  output_path = "${path.module}/.terraform/pubsub_lambda.zip"

  source {
    content  = local.lambda_code
    filename = "index.js"
  }
}

resource "aws_lambda_function" "pubsub" {
  filename         = data.archive_file.lambda_zip.output_path
  function_name    = "${var.prefix}-pubsub"
  role             = aws_iam_role.lambda.arn
  handler          = "index.handler"
  runtime          = "nodejs20.x"
  source_code_hash = data.archive_file.lambda_zip.output_base64sha256
  timeout          = 30
  memory_size      = 256

  environment {
    variables = {
      TABLE_NAME = aws_dynamodb_table.connections.name
    }
  }

  tags = local.default_tags
}

resource "aws_cloudwatch_log_group" "lambda" {
  name              = "/aws/lambda/${aws_lambda_function.pubsub.function_name}"
  retention_in_days = 14

  tags = local.default_tags
}

################################################################################
# API Gateway WebSocket API
################################################################################

resource "aws_apigatewayv2_api" "websocket" {
  name                       = "${var.prefix}-pubsub"
  protocol_type              = "WEBSOCKET"
  route_selection_expression = "$request.body.action"

  tags = local.default_tags
}

resource "aws_apigatewayv2_stage" "prod" {
  api_id      = aws_apigatewayv2_api.websocket.id
  name        = "prod"
  auto_deploy = true

  default_route_settings {
    throttling_burst_limit = 5000
    throttling_rate_limit  = 10000
  }

  tags = local.default_tags
}

resource "aws_apigatewayv2_integration" "lambda" {
  api_id             = aws_apigatewayv2_api.websocket.id
  integration_type   = "AWS_PROXY"
  integration_uri    = aws_lambda_function.pubsub.invoke_arn
  integration_method = "POST"
}

resource "aws_apigatewayv2_route" "connect" {
  api_id    = aws_apigatewayv2_api.websocket.id
  route_key = "$connect"
  target    = "integrations/${aws_apigatewayv2_integration.lambda.id}"
}

resource "aws_apigatewayv2_route" "disconnect" {
  api_id    = aws_apigatewayv2_api.websocket.id
  route_key = "$disconnect"
  target    = "integrations/${aws_apigatewayv2_integration.lambda.id}"
}

resource "aws_apigatewayv2_route" "default" {
  api_id    = aws_apigatewayv2_api.websocket.id
  route_key = "$default"
  target    = "integrations/${aws_apigatewayv2_integration.lambda.id}"
}

resource "aws_lambda_permission" "apigw" {
  statement_id  = "AllowAPIGateway"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.pubsub.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.websocket.execution_arn}/*/*"
}

################################################################################
# Outputs
################################################################################

output "websocket_url" {
  description = "WebSocket URL for connecting to pub/sub (use with aws-wss:// scheme)"
  value       = replace(aws_apigatewayv2_stage.prod.invoke_url, "wss://", "")
}

output "websocket_url_full" {
  description = "Full WebSocket URL (wss://...)"
  value       = aws_apigatewayv2_stage.prod.invoke_url
}

output "api_id" {
  description = "API Gateway WebSocket API ID"
  value       = aws_apigatewayv2_api.websocket.id
}

output "lambda_function_name" {
  description = "Lambda function name"
  value       = aws_lambda_function.pubsub.function_name
}

output "dynamodb_table_name" {
  description = "DynamoDB table name for connection tracking"
  value       = aws_dynamodb_table.connections.name
}

output "kotlin_settings" {
  description = "Copy-paste this into your Kotlin code"
  value       = "PubSub.Settings(\"aws-wss://${replace(aws_apigatewayv2_stage.prod.invoke_url, "wss://", "")}\")"
}
