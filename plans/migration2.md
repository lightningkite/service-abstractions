# Module Migration Guide: From Lightning Server to Service Abstractions

This guide provides a succinct, step-by-step workflow for migrating modules from Lightning Server to the Service Abstractions project. It complements the detailed technical migration guide in [migration.md](migration.md) by focusing on organizational aspects and practical steps.

## Migration Workflow

### 1. Module Structure Setup

1. **Create module directories**:
   ```
   module/                           # Core module with interfaces
   module-implementation1/           # Implementation-specific module
   module-implementation2/           # Another implementation
   module-test/                      # Shared test utilities
   ```

2. **Configure build files**:
   - Core module: Use `kotlin-multiplatform` when possible
   - Implementation modules: Use appropriate plugins based on dependencies
   - Add explicit API mode to all modules: `kotlin { explicitApi() }`

3. **Set up package structure**:
   ```
   com.lightningkite.serviceabstractions.module/       # Core interfaces
   com.lightningkite.serviceabstractions.module.impl/  # Implementation
   ```

### 2. Interface Migration

1. **Move and refactor the interface**:
   - Place in `commonMain` source set when possible
   - Extend `Service` interface
   - Add explicit visibility modifiers
   - Create nested `Settings` value class

2. **Create abstract base classes**:
   - `MetricTracking[Service]` for metrics implementation
   - Use template method pattern (abstract protected methods, public methods with metrics)

3. **Separate extension functions**:
   - Move to a dedicated `.ext.kt` file
   - Use the instance's `serializersModule` property

### 3. Implementation Migration

1. **Move basic implementations to core module**:
   - In-memory implementations (e.g., `MapCache`)
   - Test doubles and mocks

2. **Create specialized implementation modules**:
   - One module per implementation technology (e.g., Redis, Memcached)
   - Implement platform-specific code in appropriate source sets
   - Use dependency injection via constructors

3. **Platform-specific settings**:
   - Create platform-specific registration functions
   - Use source set separation for JVM vs non-JVM code

### 4. Terraform Generation

1. **Create terraform extension functions**:
   - Add a `tf.kt` file in each module
   - Implement extension functions on `TerraformNeed<Service>`
   - Return `TerraformServiceResult<Service>`

2. **Basic implementation in core module**:
   ```kotlin
   public fun TerraformNeed<Service>.ram(): TerraformServiceResult<Service> = 
       TerraformServiceResult<Service>(
           need = this,
           terraformExpression = "ram://",
           out = TerraformJsonObject()
       )
   ```

3. **Specialized implementations in implementation modules**:
   ```kotlin
   public fun TerraformNeed<Service>.awsImplementation(
       // Configuration parameters
   ): TerraformServiceResult<Service> = TerraformServiceResult(
       need = this,
       terraformExpression = "implementation://\${resource.name}",
       out = terraformJsonObject {
           // Resource definitions
       }
   )
   ```

### 5. Testing

1. **Create shared test utilities**:
   - Place in `module-test` or in core module's test source set
   - Implement contract tests that verify interface compliance

2. **Test each implementation**:
   - Create implementation-specific tests
   - Verify platform-specific behavior
   - Test terraform generation

3. **Integration tests**:
   - Test with other services when appropriate
   - Verify metrics and health checks

## Practical Migration Checklist

1. [ ] **Analyze the existing implementation**
   - Identify core interface and methods
   - List all implementations and their dependencies
   - Note platform-specific code

2. [ ] **Set up module structure**
   - Create module directories
   - Configure build files
   - Set up package structure

3. [ ] **Migrate core interface**
   - Refactor interface to extend `Service`
   - Create nested `Settings` class
   - Add explicit visibility modifiers
   - Create abstract base classes for metrics

4. [ ] **Migrate implementations**
   - Move basic implementations to core module
   - Create specialized implementation modules
   - Implement platform-specific code

5. [ ] **Add terraform generation**
   - Create terraform extension functions in each module
   - Implement basic and specialized terraform generation

6. [ ] **Write tests**
   - Create shared test utilities
   - Test each implementation
   - Add integration tests

7. [ ] **Update documentation**
   - Add KDoc comments
   - Update README files
   - Add examples

## Common Pitfalls to Avoid

1. **Dependency Leakage**: Don't let implementation-specific dependencies leak into the core module
2. **Platform Assumptions**: Avoid assuming JVM-only features in multiplatform code
3. **Static Singletons**: Use dependency injection instead of static instances
4. **Implicit Visibility**: Always use explicit visibility modifiers
5. **Monolithic Design**: Keep modules focused and separate concerns

## Example Directory Structure

```
module/
├── build.gradle.kts                 # Core module build file
├── src/
│   ├── commonMain/
│   │   └── kotlin/com/lightningkite/serviceabstractions/module/
│   │       ├── Module.kt            # Core interface
│   │       ├── Module.ext.kt        # Extension functions
│   │       ├── MetricTrackingModule.kt  # Abstract base class
│   │       ├── BasicImplementation.kt   # Simple implementation
│   │       └── tf.kt                # Basic terraform generation
│   ├── commonJvmMain/               # JVM-specific code
│   ├── nonJvmMain/                  # Non-JVM code
│   └── commonTest/                  # Tests
│
module-implementation1/
├── build.gradle.kts                 # Implementation module build file
└── src/
    └── main/kotlin/com/lightningkite/serviceabstractions/module/impl1/
        ├── Implementation1.kt       # Specialized implementation
        └── tf.kt                    # Specialized terraform generation
```

By following this guide, you can efficiently migrate modules from Lightning Server to the Service Abstractions project while maintaining consistency and following best practices.