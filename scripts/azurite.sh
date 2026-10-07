#!/usr/bin/env bash
#
# Runs Azurite (Microsoft's Blob Storage emulator) for the files-azure-blob tests, over HTTPS with
# OAuth enabled so both the account-key and the Azure-identity code paths can be exercised.
#
# On first run it creates a self-signed certificate for 127.0.0.1 and a truststore holding it plus
# the JDK's normal CAs, in local/azurite/. (.pfx and .crt rather than .p12 and .pem because some sandboxes block
# writing those.) files-azure-blob's test task points the JVM at that
# truststore when it exists.
#
# Usage: scripts/azurite.sh      (then, in another shell: ./gradlew :files-azure-blob:test)
set -euo pipefail

dir="$(cd "$(dirname "$0")/.." && pwd)/local/azurite"
mkdir -p "$dir"
cd "$dir"

if [ ! -f truststore.pfx ]; then
  keytool -genkeypair -alias azurite -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=127.0.0.1" -ext "SAN=IP:127.0.0.1,DNS:localhost" \
    -storetype PKCS12 -keystore cert.pfx -storepass azurite -keypass azurite
  keytool -exportcert -rfc -alias azurite -keystore cert.pfx -storepass azurite -file cert.crt
  java_home="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  keytool -importkeystore -srckeystore "$java_home/lib/security/cacerts" -srcstorepass changeit \
    -destkeystore truststore.pfx -deststoretype PKCS12 -deststorepass azurite -noprompt
  keytool -importcert -alias azurite -file cert.crt -keystore truststore.pfx -storepass azurite -noprompt
fi

# Azurite fetches copy sources itself, over HTTPS, so Node must trust the certificate too.
export NODE_EXTRA_CA_CERTS="$dir/cert.crt"
exec npx --yes --package=azurite@3 azurite-blob --silent --location data --blobHost 127.0.0.1 --blobPort 10000 \
  --cert cert.pfx --pwd azurite --oauth basic --loose --skipApiVersionCheck
