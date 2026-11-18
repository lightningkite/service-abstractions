# Terraform

Implementation modules can generate the Terraform for their own infrastructure and fill in the matching setting: `awsS3Bucket()` on a files setting emits the bucket, its CORS rules and IAM policy, and sets the URL to `s3://<that bucket>...`.  Lightning Server drives this to produce a whole deployment; the pieces live here.

## Modules

| Module | Add it for |
|---|---|
| `basis` | `TerraformNeed`, `TerraformEmitter`, `TerraformEmitterAws`, `TerraformJsonObject`, and the generic `direct`/`byVariable` fulfillments. |
| each implementation module | Its helpers, in a `tf.kt` (table below). |
| `test` | `TerraformEmitterAwsTest`, for testing helpers by running `terraform`. |

## Quick start

```kotlin
context(emitter: TerraformEmitterAws)
fun infrastructure() {
    TerraformNeed<Database.Settings>("database").mongodbAtlasFlex(orgId = "5f...")
    TerraformNeed<Cache.Settings>("cache").awsElasticacheRedisServerless()
    TerraformNeed<ExternalFileSystem.Settings>("files").awsS3Bucket(signedUrlDuration = 1.hours)
    TerraformNeed<String>("adminEmail").byVariable()   // left as a Terraform variable
}
```

A `TerraformNeed<T>` names one setting.  Helpers that need a domain (`awsSesSmtp`, `awsSesInbound`) require a `TerraformEmitterAwsDomain`.  A helper emits resources through the `emitter` and calls `fulfillSetting(name, value)` with the setting's value, which may reference Terraform outputs.  The emitter (Lightning Server's, or your own) collects the resources, providers, IAM statements and settings into `.tf.json` files.

Helpers check that their implementation is registered, so reference it first (`MongoDatabase`, `RedisCache`, `S3ExternalFileSystem`, ...), as for parsing settings.

## Helpers

| Setting | Helper | Module |
|---|---|---|
| `Database.Settings` | `mongodbAtlas`, `mongodbAtlasFlex`, `mongodbAtlasFree`, `awsDocumentDb` | `database-mongodb` |
| | `awsAuroraServerlessV1`, `awsAuroraServerlessV2` | `database-postgres` |
| `Cache.Settings` | `awsElasticacheRedis`, `awsElasticacheRedisServerless`, `awsElasticacheValkey`, `awsElasticacheValkeyServerless`, `redis` | `cache-redis` |
| | `awsElasticacheMemcached`, `awsElasticacheMemcachedServerless` | `cache-memcached` |
| | `awsDynamoDb` | `cache-dynamodb` |
| `PubSub.Settings` | `awsElasticacheRedis`, `awsElasticacheRedisReplicationGroup`, `awsElasticacheRedisServerless`, `awsElasticacheValkey`, `awsElasticacheValkeyServerless`, `redis` | `pubsub-redis` |
| | `dynamoDb` | `pubsub-aws` |
| `ExternalFileSystem.Settings` | `awsS3Bucket` | `files-s3` |
| `FileScanner.Settings` | `awsEc2ClamAv`, `clamav` | `files-clamav` |
| `EmailService.Settings` | `awsSesSmtp` | `email-javasmtp` |
| `EmailInboundService.Settings` | `awsSesInbound` | `email-inbound-ses` |
| | `mailgunRoutes` | `email-inbound-mailgun` |
| | `sendgridInboundParse` | `email-inbound-sendgrid` |
| `LlmAccess.Settings` | `anthropic`, `openai`, `awsBedrock`, `ollama` | `ai-anthropic`, `ai-openai`, `ai-bedrock`, `ai-ollama` |
| `Geocoding.Settings` | `awsLocation` | `geocoding-aws` |
| `SubscriptionService.Settings` | `stripeWebhook` | `subscription-payments-stripe` |
| any | `direct(value)`, `byVariable()` | `basis` |

Each helper's KDoc lists its parameters and the resources it creates.

## Testing

`TerraformEmitterAwsTest` (in `test`) collects a helper's output into a folder, runs `terraform plan`, and can `apply()` (returning the fulfilled setting) and `destroy()`.  The AWS modules' integration tests use it against a real account.

## Gotchas

- **Replacing hosts**: some helpers bake values into instance user data (ClamAV on EC2).  Changing those inputs replaces the instance on the next apply.
- **Defaults cost money differently**: serverless variants scale to near zero; provisioned ones bill by the hour.  Check the helper's defaults before applying to production.
