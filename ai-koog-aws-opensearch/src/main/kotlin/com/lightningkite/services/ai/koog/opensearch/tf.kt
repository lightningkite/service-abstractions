package com.lightningkite.services.ai.koog.opensearch

import ai.koog.rag.vector.VectorStorage
import com.lightningkite.services.Untested
import com.lightningkite.services.ai.koog.rag.VectorStorageSettings
import com.lightningkite.services.terraform.AwsPolicyStatement
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.json.JsonPrimitive

/**
 * Creates an AWS OpenSearch domain for vector storage using Terraform.
 *
 * This function generates Terraform configuration for an OpenSearch domain with k-NN plugin enabled,
 * suitable for RAG (Retrieval-Augmented Generation) applications using Koog's vector storage.
 *
 * Features:
 * - OpenSearch domain with k-NN plugin for vector search
 * - Configurable instance type and volume size
 * - Fine-grained access control with master user credentials
 * - Encryption at rest and in transit
 * - IAM policy granting necessary OpenSearch permissions
 *
 * The generated domain will be accessible via HTTPS with basic authentication.
 * The connection URL is automatically configured with the master username and password
 * stored in the Terraform state.
 *
 * @param vectorDimension The dimension of vectors to be stored (required for index configuration)
 * @param indexName The name of the OpenSearch index to create (default: "vectors")
 * @param instanceType The OpenSearch instance type (default: "t3.small.search" for cost-effective development)
 * @param instanceCount The number of instances in the OpenSearch domain (default: 1)
 * @param volumeSize The EBS volume size in GB for each instance (default: 10)
 * @param masterUsername The master username for OpenSearch authentication (default: "admin")
 * @param masterPassword The master password for OpenSearch authentication.
 *                      If null, a random password will be generated via Terraform.
 * @throws IllegalArgumentException if OpenSearchVectorStorage is not registered in VectorStorageSettings
 */
@Untested
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<VectorStorage<*>>.awsOpenSearchDomain(
    vectorDimension: Int,
    indexName: String = "vectors",
    instanceType: String = "t3.small.search",
    instanceCount: Int = 1,
    volumeSize: Int = 10,
    masterUsername: String = "admin",
    masterPassword: String? = null,
): Unit {
    if (!VectorStorageSettings.supports("opensearch")) {
        throw IllegalArgumentException("You need to reference OpenSearchVectorStorage in your server definition to use this.")
    }

    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    emitter.emit(name) {
        // Generate a random password if not provided
        if (masterPassword == null) {
            "resource.random_password.${name}_master_password" {
                "length" - 16
                "special" - true
                "override_special" - "!@#$$%^&*()"
            }
        }

        // Create OpenSearch domain
        "resource.aws_opensearch_domain.$name" {
            "domain_name" - "${emitter.projectPrefix}-${name.lowercase()}"
            "engine_version" - "OpenSearch_2.11"

            // Cluster configuration
            "cluster_config" {
                "instance_type" - instanceType
                "instance_count" - instanceCount
                "dedicated_master_enabled" - false
                "zone_awareness_enabled" - false
            }

            // EBS storage
            "ebs_options" {
                "ebs_enabled" - true
                "volume_size" - volumeSize
                "volume_type" - "gp3"
            }

            // Advanced options (k-NN plugin enabled by default in OpenSearch)
            "advanced_options" {
                "rest.action.multi.allow_explicit_index" - "true"
            }

            // Encryption at rest
            "encrypt_at_rest" {
                "enabled" - true
            }

            // Node-to-node encryption
            "node_to_node_encryption" {
                "enabled" - true
            }

            // Domain endpoint options
            "domain_endpoint_options" {
                "enforce_https" - true
                "tls_security_policy" - "Policy-Min-TLS-1-2-2019-07"
            }

            // Fine-grained access control
            "advanced_security_options" {
                "enabled" - true
                "internal_user_database_enabled" - true
                "master_user_options" {
                    "master_user_name" - masterUsername
                    "master_user_password" - if (masterPassword != null) {
                        masterPassword
                    } else {
                        expression("random_password.${name}_master_password.result")
                    }
                }
            }

            // Access policy
            "access_policies" - $$"""
            {
                "Version": "2012-10-17",
                "Statement": [
                    {
                        "Effect": "Allow",
                        "Principal": {
                            "AWS": "*"
                        },
                        "Action": "es:*",
                        "Resource": "arn:aws:es:${emitter.applicationRegion}:*:domain/${emitter.projectPrefix}-${name.lowercase()}/*"
                    }
                ]
            }
            """
        }

        // Output the domain endpoint
        "output.${name}_endpoint" {
            "value" - expression("aws_opensearch_domain.$name.endpoint")
            "description" - "OpenSearch domain endpoint"
        }
    }

    // Fulfill the VectorStorage setting with the connection URL
    val passwordRef = if (masterPassword != null) {
        masterPassword
    } else {
        $$"${random_password.${name}_master_password.result}"
    }

    emitter.fulfillSetting(
        name, JsonPrimitive(
            $$"opensearch://${aws_opensearch_domain.$name.endpoint}:443/$indexName?dimension=$vectorDimension&user=$masterUsername&password=$passwordRef"
        )
    )

    // Add IAM policy for OpenSearch access
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf(
            "es:ESHttpGet",
            "es:ESHttpPut",
            "es:ESHttpPost",
            "es:ESHttpDelete",
            "es:ESHttpHead"
        ),
        resource = listOf(
            $$"arn:aws:es:${emitter.applicationRegion}:*:domain/${emitter.projectPrefix}-${name.lowercase()}/*"
        )
    )
}

/*
 * TODO: Terraform API Enhancement Recommendations
 *
 * 1. VPC Integration: Add optional VPC configuration for private domain access
 *    - vpc_options parameter for subnet and security group configuration
 *    - Useful for production environments requiring network isolation
 *
 * 2. Advanced k-NN Configuration: Add parameters for k-NN specific settings
 *    - knn.algo_param.m (default: 16)
 *    - knn.algo_param.ef_construction (default: 512)
 *    - These affect index build time vs search performance trade-offs
 *
 * 3. Snapshot Configuration: Add automated snapshot configuration
 *    - snapshot_options with automated snapshot start hour
 *    - Useful for backup and disaster recovery
 *
 * 4. CloudWatch Logging: Add optional CloudWatch log publishing
 *    - log_publishing_options for INDEX_SLOW_LOGS, SEARCH_SLOW_LOGS, ES_APPLICATION_LOGS
 *    - Useful for monitoring and debugging
 *
 * 5. Auto-Tune: Add optional auto-tune configuration
 *    - auto_tune_options for automatic performance tuning
 *    - Rollback support for failed deployments
 *
 * 6. Index Management: Consider adding index template creation via Terraform
 *    - Using terraform-provider-opensearch or null_resource with local-exec
 *    - Pre-configure k-NN index settings for the specified dimension
 *
 * 7. Multi-AZ: Add parameter for multi-AZ deployment
 *    - zone_awareness_enabled and availability_zone_count
 *    - Improves availability but increases cost
 *
 * 8. Dedicated Master: Add option for dedicated master nodes
 *    - dedicated_master_enabled, dedicated_master_type, dedicated_master_count
 *    - Recommended for production workloads
 */
