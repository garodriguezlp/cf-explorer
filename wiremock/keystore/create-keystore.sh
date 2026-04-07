#!/usr/bin/env bash
# Recreates wiremock-keystore.jks with two self-signed RSA entries and prints the base64 value
# suitable for pasting into v3-app-env-vars.json.
# Requires: $JAVA_HOME set (Java 17+), base64 on PATH (standard on Linux/macOS/MinGW).

set -euo pipefail

KEYTOOL="${JAVA_HOME}/bin/keytool"
KS="wiremock/keystore/wiremock-keystore.jks"
PASS="changeit"

rm -f "$KS"

"$KEYTOOL" -genkeypair \
  -alias app-tls \
  -keyalg RSA -keysize 2048 -validity 365 \
  -keystore "$KS" -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=app-tls.example.com, OU=Platform, O=Example Corp, L=London, C=GB"

"$KEYTOOL" -genkeypair \
  -alias service-mtls \
  -keyalg RSA -keysize 2048 -validity 730 \
  -keystore "$KS" -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=service-mtls.example.com, OU=Platform, O=Example Corp, L=London, C=GB"

echo ""
echo "=== Base64 value for KEYSTORE env var ==="
base64 "$KS"
echo ""
echo "=== Keystore entries ==="
"$KEYTOOL" -list -v -keystore "$KS" -storepass "$PASS"
