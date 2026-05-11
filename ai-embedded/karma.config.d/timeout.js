// Increase timeouts for integration tests that download models from HuggingFace Hub.
config.set({
    client: {
        mocha: {
            timeout: 300000 // 5 minutes
        }
    },
    browserNoActivityTimeout: 300000,
    browserDisconnectTimeout: 30000
});
