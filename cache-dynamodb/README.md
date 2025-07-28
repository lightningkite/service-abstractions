# Cache DynamoDB

This module provides a DynamoDB implementation of the Cache interface from the service-abstractions project.

## Installation

Add the dependency to your project:

```kotlin
dependencies {
    implementation("com.lightningkite:service-abstractions-cache-dynamodb:0.0.1")
}
```

## Usage

### Initialization

Initialize the DynamoDB cache implementation at application startup:

```kotlin
import com.lightningkite.serviceabstractions.cache.dynamodb.initDynamoDbCache

fun main() {
    // Initialize the DynamoDB cache implementation
    initDynamoDbCache()
    
    // Rest of your application code
}
```

### Configuration

You can configure the DynamoDB cache using a URL in your configuration:

```
cache.url=dynamodb://[access]:[secret]@[region]/[tableName]
```

Where:
- `[access]` is your AWS access key (optional if using default credentials)
- `[secret]` is your AWS secret key (optional if using default credentials)
- `[region]` is the AWS region (e.g., us-west-2)
- `[tableName]` is the name of the DynamoDB table to use for caching

Example:
```
cache.url=dynamodb://us-west-2/my-cache-table
```

### Direct Usage

You can also create a DynamoDB cache instance directly:

```kotlin
import com.lightningkite.serviceabstractions.cache.dynamodb.DynamoDbCache
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.regions.Region

val cache = DynamoDbCache(
    makeClient = {
        DynamoDbAsyncClient.builder()
            .region(Region.US_WEST_2)
            .build()
    },
    tableName = "my-cache-table",
    context = mySettingContext
)
```

### Terraform Integration

This module provides Terraform integration for creating the necessary DynamoDB resources:

```kotlin
import com.lightningkite.serviceabstractions.cache.Cache
import com.lightningkite.serviceabstractions.cache.dynamodb.dynamodb
import com.lightningkite.serviceabstractions.terraform.TerraformNeed

// Create a TerraformNeed for Cache
val cacheNeed = TerraformNeed<Cache>(cloudInfo)

// Generate Terraform configuration for DynamoDB
val cacheResult = cacheNeed.dynamodb(
    region = "us-west-2",
    tableName = "my-cache-table",
    tags = mapOf("Environment" to "production")
)

// Use the result in your Terraform configuration
val terraformConfig = cacheResult.out
```

## Features

- Implements the Cache interface using Amazon DynamoDB
- Automatically creates the DynamoDB table if it doesn't exist
- Configures Time-to-Live (TTL) for cache entries
- Provides Terraform integration for infrastructure as code
- Supports both default and explicit AWS credentials

## Implementation Details

The DynamoDB cache implementation:
- Uses a hash key named "key" for cache keys
- Stores values in an attribute named "value"
- Uses an attribute named "expires" for TTL
- Configures the table with PAY_PER_REQUEST billing mode for automatic scaling