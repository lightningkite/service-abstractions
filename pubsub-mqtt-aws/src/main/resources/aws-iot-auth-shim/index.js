/**
 * AWS IoT Core Custom Authorizer Lambda
 *
 * This is a thin shim that forwards authentication requests to your
 * application server's HTTP endpoint. This keeps all auth logic in
 * your main application rather than in separate Lambda code.
 *
 * Environment Variables:
 * - AUTH_ENDPOINT: Full URL to your /mqtt/auth endpoint (configured at runtime)
 * - AUTH_TIMEOUT_MS: Timeout for HTTP call (default: 4000, must be < 5000)
 */

const https = require('https');
const http = require('http');
const url = require('url');

exports.handler = async (event) => {
    console.log('Received event:', JSON.stringify(event, null, 2));

    const authEndpoint = process.env.AUTH_ENDPOINT;
    if (!authEndpoint) {
        console.error('AUTH_ENDPOINT environment variable not set');
        return buildDenyResponse();
    }

    const timeoutMs = parseInt(process.env.AUTH_TIMEOUT_MS || '4000', 10);

    // Extract MQTT connection info from event
    const mqttContext = event.protocolData?.mqtt || {};
    const tlsContext = event.protocolData?.tls || {};

    // Build request to your auth endpoint
    const authRequest = {
        clientId: mqttContext.clientId || event.clientId || 'unknown',
        username: mqttContext.username || null,
        // Password is base64 encoded in IoT Core events
        password: mqttContext.password
            ? Buffer.from(mqttContext.password, 'base64').toString('utf8')
            : null,
        sourceIp: event.connectionMetadata?.id || null,
        certificateCn: tlsContext.serverName || null,
        metadata: {
            protocols: (event.protocols || []).join(','),
            signatureVerified: String(event.signatureVerified || false),
            awsAccountId: event.awsAccountId || ''
        }
    };

    try {
        const response = await callAuthEndpoint(authEndpoint, authRequest, timeoutMs);
        console.log('Auth response:', JSON.stringify(response, null, 2));

        if (response.type === 'Deny' || response === 'Deny') {
            return buildDenyResponse();
        }

        // Map our response format to AWS IoT policy format
        return buildAllowResponse(response, mqttContext.clientId);

    } catch (error) {
        console.error('Auth endpoint error:', error.message);
        return buildDenyResponse();
    }
};

async function callAuthEndpoint(endpoint, body, timeoutMs) {
    return new Promise((resolve, reject) => {
        const parsedUrl = url.parse(endpoint);
        const lib = parsedUrl.protocol === 'https:' ? https : http;

        const options = {
            hostname: parsedUrl.hostname,
            port: parsedUrl.port || (parsedUrl.protocol === 'https:' ? 443 : 80),
            path: parsedUrl.path,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Forwarded-For-Source': 'aws-iot-authorizer'
            },
            timeout: timeoutMs
        };

        const req = lib.request(options, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                if (res.statusCode === 200) {
                    try {
                        resolve(JSON.parse(data));
                    } catch (e) {
                        reject(new Error(`Invalid JSON response: ${data}`));
                    }
                } else {
                    reject(new Error(`HTTP ${res.statusCode}: ${data}`));
                }
            });
        });

        req.on('error', reject);
        req.on('timeout', () => {
            req.destroy();
            reject(new Error('Request timeout'));
        });

        req.write(JSON.stringify(body));
        req.end();
    });
}

function buildAllowResponse(authResponse, clientId) {
    const principalId = authResponse.principalId || clientId || 'unknown';
    const statements = [];

    // Connect permission (always needed)
    statements.push({
        Effect: 'Allow',
        Action: 'iot:Connect',
        Resource: `arn:aws:iot:*:*:client/${clientId}`
    });

    // Publish permissions
    const publishTopics = authResponse.publishTopics || [];
    if (publishTopics.length > 0) {
        statements.push({
            Effect: 'Allow',
            Action: 'iot:Publish',
            Resource: publishTopics.map(topic =>
                `arn:aws:iot:*:*:topic/${replaceClientId(topic, clientId)}`
            )
        });
    }

    // Subscribe permissions
    const subscribeTopics = authResponse.subscribeTopics || [];
    if (subscribeTopics.length > 0) {
        // Subscribe action
        statements.push({
            Effect: 'Allow',
            Action: 'iot:Subscribe',
            Resource: subscribeTopics.map(topic =>
                `arn:aws:iot:*:*:topicfilter/${replaceClientId(topic, clientId)}`
            )
        });

        // Receive action (needed to actually get messages)
        statements.push({
            Effect: 'Allow',
            Action: 'iot:Receive',
            Resource: subscribeTopics.map(topic =>
                `arn:aws:iot:*:*:topic/${replaceClientId(topic, clientId)}`
            )
        });
    }

    const policyDocument = {
        Version: '2012-10-17',
        Statement: statements
    };

    return {
        isAuthenticated: true,
        principalId: principalId,
        disconnectAfterInSeconds: authResponse.disconnectAfterSeconds || 86400,
        refreshAfterInSeconds: 300,
        policyDocuments: [JSON.stringify(policyDocument)]
    };
}

function buildDenyResponse() {
    return {
        isAuthenticated: false,
        principalId: 'denied',
        disconnectAfterInSeconds: 0,
        refreshAfterInSeconds: 0,
        policyDocuments: []
    };
}

// Replace ${clientId} placeholder with actual client ID
function replaceClientId(topic, clientId) {
    return topic.replace(/\$\{clientId\}/g, clientId);
}
