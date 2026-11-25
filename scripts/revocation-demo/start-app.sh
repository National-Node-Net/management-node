#!/usr/bin/env bash
#
# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
#

# Starts Management Node against the test Vault. The log stays in this window and in work/app.log (Ctrl+C stops it).
# Run from the repository root in Git Bash, AFTER setup-vault.sh and ./mvnw package -DskipTests:
#   bash scripts/revocation-demo/start-app.sh
source "$(dirname "${BASH_SOURCE[0]}")/demo-env.sh"
cd "$ROOT"
set -a; . docker/keycloak/.env; set +a           # POSTGRES_PASSWORD for the application's datasource
export CERTPASSWORD=${CERTPASSWORD:-changeit}    # password of keystore.jks and the truststore

# Overrides of application.yml, all for this local demo only:
#   vault.*                     the test Vault and its root token (the environments log in with AppRole)
#   server.ssl.trust-store      the truststore from setup-vault.sh, which trusts the test CA
#   opaquetoken.client-secret   management-node's secret, used to introspect tokens, read live from Keycloak
#   application.opa.enabled     no OPA server in this demo
java -jar target/management-node-*.jar \
  --server.port="$MN_PORT" \
  --management.server.port="$MN_ACTUATOR" \
  --spring.cloud.vault.uri="http://localhost:$VAULT_PORT" \
  --spring.cloud.vault.authentication=token \
  --spring.cloud.vault.token="$VAULT_TOKEN" \
  --server.ssl.trust-store="${WORK#"$ROOT"/}/truststore-demo.jks" \
  --spring.security.oauth2.resourceserver.opaquetoken.client-secret="$(kc_secret management-node)" \
  --application.opa.enabled=false \
  2>&1 | tee "$WORK/app.log"
