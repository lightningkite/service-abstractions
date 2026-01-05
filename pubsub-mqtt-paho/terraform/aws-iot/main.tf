################################################################################
# AWS IoT Core MQTT - Terraform Configuration
#
# This Terraform module deploys AWS IoT Core resources for MQTT testing:
# - IoT Thing (represents the client)
# - IoT Certificate (for authentication)
# - IoT Policy (permissions)
#
# Usage:
#   cd pubsub-mqtt-paho/terraform/aws-iot
#   terraform init
#   terraform apply -var="prefix=test"
#
# After deployment, use the outputs to configure your MQTT client:
#   mqtt://[endpoint]:8883?clientId=[thing_name]&certFile=[cert]&keyFile=[key]
#
################################################################################

terraform {
  required_version = ">= 1.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 4.0"
    }
  }
}

variable "prefix" {
  description = "Resource name prefix"
  type        = string
  default     = "mqtt-test"
}

variable "tags" {
  description = "Tags to apply to all resources"
  type        = map(string)
  default     = {}
}

locals {
  default_tags = merge(var.tags, {
    Service = "mqtt-pubsub"
    Module  = "pubsub-mqtt-paho"
  })
}

################################################################################
# Data Sources
################################################################################

data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

# Get the IoT endpoint for this region
data "aws_iot_endpoint" "mqtt" {
  endpoint_type = "iot:Data-ATS"
}

################################################################################
# IoT Certificate and Keys
################################################################################

resource "aws_iot_certificate" "cert" {
  active = true
}

################################################################################
# IoT Policy - Allow all MQTT operations for testing
################################################################################

resource "aws_iot_policy" "policy" {
  name = "${var.prefix}-mqtt-policy"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "iot:Connect",
          "iot:Publish",
          "iot:Subscribe",
          "iot:Receive"
        ]
        Resource = "*"
      }
    ]
  })
}

################################################################################
# Attach Policy to Certificate
################################################################################

resource "aws_iot_policy_attachment" "att" {
  policy = aws_iot_policy.policy.name
  target = aws_iot_certificate.cert.arn
}

################################################################################
# IoT Thing
################################################################################

resource "aws_iot_thing" "thing" {
  name = "${var.prefix}-mqtt-thing"

  attributes = {
    Purpose = "Testing"
  }
}

################################################################################
# Attach Certificate to Thing
################################################################################

resource "aws_iot_thing_principal_attachment" "att" {
  principal = aws_iot_certificate.cert.arn
  thing     = aws_iot_thing.thing.name
}

################################################################################
# Local Files - Save Certificate and Keys
################################################################################

# Save certificate to local file
resource "local_file" "cert_pem" {
  content         = aws_iot_certificate.cert.certificate_pem
  filename        = "${path.module}/certificates/certificate.pem.crt"
  file_permission = "0600"
}

# Save private key to local file
resource "local_sensitive_file" "private_key" {
  content         = aws_iot_certificate.cert.private_key
  filename        = "${path.module}/certificates/private.pem.key"
  file_permission = "0600"
}

# Save public key to local file
resource "local_file" "public_key" {
  content         = aws_iot_certificate.cert.public_key
  filename        = "${path.module}/certificates/public.pem.key"
  file_permission = "0600"
}

# Download Amazon Root CA certificate
resource "null_resource" "download_root_ca" {
  provisioner "local-exec" {
    command = <<-EOT
      mkdir -p ${path.module}/certificates
      curl -o ${path.module}/certificates/AmazonRootCA1.pem https://www.amazontrust.com/repository/AmazonRootCA1.pem
    EOT
  }

  triggers = {
    always_run = timestamp()
  }
}

# Convert private key to PKCS#8 format for Java compatibility
# AWS IoT generates PKCS#1 format (BEGIN RSA PRIVATE KEY) but Java requires PKCS#8 (BEGIN PRIVATE KEY)
resource "null_resource" "convert_key_to_pkcs8" {
  depends_on = [local_sensitive_file.private_key]

  provisioner "local-exec" {
    command = <<-EOT
      openssl pkcs8 -topk8 -nocrypt \
        -in ${path.module}/certificates/private.pem.key \
        -out ${path.module}/certificates/private.pkcs8.key
      chmod 600 ${path.module}/certificates/private.pkcs8.key
    EOT
  }

  triggers = {
    key_changed = aws_iot_certificate.cert.private_key
  }
}

################################################################################
# Outputs
################################################################################

output "iot_endpoint" {
  description = "AWS IoT Core endpoint (host only)"
  value       = data.aws_iot_endpoint.mqtt.endpoint_address
}

output "iot_endpoint_full" {
  description = "Full MQTT endpoint URL (mqtts://...)"
  value       = "mqtts://${data.aws_iot_endpoint.mqtt.endpoint_address}:8883"
}

output "thing_name" {
  description = "IoT Thing name (use as client ID)"
  value       = aws_iot_thing.thing.name
}

output "certificate_arn" {
  description = "Certificate ARN"
  value       = aws_iot_certificate.cert.arn
}

output "certificate_pem_file" {
  description = "Path to certificate PEM file"
  value       = local_file.cert_pem.filename
}

output "private_key_file" {
  description = "Path to private key file (PKCS#8 format for Java compatibility)"
  value       = "${path.module}/certificates/private.pkcs8.key"
  sensitive   = true
}

output "root_ca_file" {
  description = "Path to Amazon Root CA file"
  value       = "${path.module}/certificates/AmazonRootCA1.pem"
}

output "connection_info" {
  description = "Copy this for your connection string"
  value       = <<-EOT
    Endpoint: ${data.aws_iot_endpoint.mqtt.endpoint_address}:8883
    Client ID: ${aws_iot_thing.thing.name}
    Certificate: ${local_file.cert_pem.filename}
    Private Key: ${path.module}/certificates/private.pkcs8.key
    Root CA: ${path.module}/certificates/AmazonRootCA1.pem
  EOT
}
