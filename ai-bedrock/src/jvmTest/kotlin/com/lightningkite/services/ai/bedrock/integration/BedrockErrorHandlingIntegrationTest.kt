package com.lightningkite.services.ai.bedrock.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.ErrorHandlingTests

/**
 * Live-API error-handling suite against Bedrock.
 *
 * [unknownModelFails] hits Bedrock's real 404 path and exercises [mapBedrockError].
 *
 * [invalidApiKeyFails] is intentionally left skipped (no `invalidCredentialsService`): on
 * Bedrock "bad creds" means a full SigV4 signature over bogus AWS access-key-id and
 * secret-access-key, and the only reliable way to mint that is to instantiate a second
 * provider with literal-string credentials. Doing so cleanly from a URL requires the static
 * `bedrock://key:secret@model` form, which works but tempts credential-like strings into log
 * output. The contract-level assertion ("some exception mentioning auth") is already covered
 * by [com.lightningkite.services.ai.bedrock.BedrockWireTest.mapsAccessDeniedToAuth] against
 * [mapBedrockError] directly, so we skip here rather than taking the hit.
 *
 * To verify manually: export obviously-bogus AWS credentials and run a single `inference`
 * call; the provider should throw [com.lightningkite.services.ai.LlmException.Auth].
 */
class BedrockErrorHandlingIntegrationTest : ErrorHandlingTests() {
    override val service: LlmAccess get() = BedrockTestConfig.service
    override val cheapModel: LlmModelId get() = BedrockTestConfig.cheapModel
    override val servicePresent: Boolean get() = BedrockTestConfig.servicePresent
    // invalidCredentialsService intentionally left null — see class kdoc.
}
