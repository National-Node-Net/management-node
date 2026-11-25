#!/usr/bin/env bash
#
# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
#

# Before the demo: a throwaway Vault with a ready PKI, and a truststore for the application that trusts its CA.
# Run from the repository root in Git Bash, BEFORE start-app.sh:
#   bash scripts/revocation-demo/setup-vault.sh
set -e
source "$(dirname "${BASH_SOURCE[0]}")/demo-env.sh"

echo "== 1. Test Vault in dev mode (container $VAULT_CONTAINER, port $VAULT_PORT, root token $VAULT_TOKEN)"
docker rm -f "$VAULT_CONTAINER" > /dev/null 2>&1 || true
docker run -d --name "$VAULT_CONTAINER" -p "127.0.0.1:$VAULT_PORT:8200" --cap-add IPC_LOCK \
  -e VAULT_DEV_ROOT_TOKEN_ID="$VAULT_TOKEN" \
  -e VAULT_DEV_LISTEN_ADDRESS=0.0.0.0:8200 \
  hashicorp/vault:1.16 > /dev/null
for i in $(seq 1 30); do vault status > /dev/null 2>&1 && break; sleep 1; done

echo "== 2. PKI as on the environments: mount pki-int, CA 'Demo Federation CA', role default-role"
vault secrets enable -path=pki-int pki > /dev/null
vault secrets tune -max-lease-ttl=87600h pki-int > /dev/null
vault write -field=certificate pki-int/root/generate/internal \
  common_name="Demo Federation CA" issuer_name=demo-ca ttl=87600h > "$WORK/ca.pem"
vault write pki-int/roles/default-role \
  allow_any_name=true enforce_hostnames=false key_type=any max_ttl=72h > /dev/null

echo "== 3. Application truststore with the new CA (otherwise Tomcat refuses the node's certificate at the mTLS handshake)"
[ -n "$KEYTOOL" ] || { echo "keytool not found: set JAVA_HOME to a JDK 21"; exit 1; }
cp "$ROOT/truststore.jks" "$WORK/truststore-demo.jks"
"$KEYTOOL" -importcert -noprompt -alias demo-federation-ca -file "$WORK/ca.pem" \
  -keystore "$WORK/truststore-demo.jks" -storepass "${CERTPASSWORD:-changeit}" > /dev/null

echo "== 4. Certificate row of organisation $ORG_KEY in the database: clear the revocation left by a previous run"
db "update mn.organisation_certificate set revoked_at=null, revoked_by=null, revocation_reason=null, revocation_message=null
    where organisation_id = (select id from mn.organisation where organisation_key = '$ORG_KEY')" > /dev/null

echo
echo "Done. CA: $(openssl x509 -in "$WORK/ca.pem" -noout -subject)"
echo "Now, in a second window: bash scripts/revocation-demo/start-app.sh"
