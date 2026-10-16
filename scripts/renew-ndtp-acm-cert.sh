#!/usr/bin/env sh
# Run from the directory containing acm-export.json:
#   sh scripts/renew-ndtp-acm-cert.sh <ACM-export-passphrase>

# to export cert from acm
# aws --profile dev acm export-certificate \
#   --certificate-arn arn:aws:acm:eu-west-2:537124944113:certificate/3647b440-b660-46f1-8072-6e257305f2f3 \
#   --passphrase "$(printf %s 'somevalues' | base64)" \
#   --output json > acm-export.json

# run this scripts/renew-ndtp-acm-cert.sh <ACM-export-passphrase.. eg somevalue>
set -eu

passphrase=$1
umask 077
tmp=$(mktemp -d)

echo "This will tch in dev/ia-management-node:"
echo "  keycloak-certs-secret"
echo "    tls.crt"
echo "    tls.key"
echo "  keycloak-cert"
echo "    keycloak.keystore.jks"
echo "    keycloak.truststore.jks"
echo "  management-node-cert"
echo "    keycloak.truststore.jks"
echo "Then it will restart: statefulset/keycloak and deployment/management-node-api"
echo

# Extract the three PEM files returned by `aws acm export-certificate`.
jq -r '.Certificate' acm-export.json > "$tmp/cert.pem"
jq -r '.CertificateChain' acm-export.json > "$tmp/chain.pem"
jq -r '.PrivateKey' acm-export.json > "$tmp/key.encrypted.pem"
openssl pkey -in "$tmp/key.encrypted.pem" -passin "pass:$passphrase" -out "$tmp/key.pem"
cat "$tmp/cert.pem" "$tmp/chain.pem" > "$tmp/fullchain.pem"

# Back up the exact three secrets before changing anything.
backup="cert-backup-$(date +%Y%m%d-%H%M%S)"
mkdir "$backup"
echo "Backing up current secrets to $backup"

# Read and back up the three secrets that will be changed.
for secret in keycloak-certs-secret keycloak-cert management-node-cert; do
  kubectl --context dev -n ia-management-node get secret "$secret" -o yaml > "$backup/$secret.yaml"
  kubectl --context dev -n ia-management-node get secret "$secret" -o json > "$tmp/$secret.json"
done
kubectl --context dev -n ia-management-node get secret keycloak-stores-secrets -o json > "$tmp/keycloak-stores-secrets.json"
jq -r '.data["tls-keystore-password"] | @base64d' "$tmp/keycloak-stores-secrets.json" > "$tmp/keystore.password"
jq -r '.data["tls-truststore-password"] | @base64d' "$tmp/keycloak-stores-secrets.json" > "$tmp/truststore.password"

# Docker supplies keytool because it is not installed on this workstation.
keytool() {
  docker run --rm -e STOREPASS -v "$tmp:/work" -w /work eclipse-temurin:21-jdk keytool "$@"
}

# keycloak.keystore.jks contains the Keycloak TLS key under alias dev-ndtp.
# Build a new PKCS#12 key entry, then swap just that alias into the old store.
jq -r '.data["keycloak.keystore.jks"]' "$tmp/keycloak-cert.json" | base64 -d > "$tmp/keycloak.keystore.jks"
STOREPASS=$(cat "$tmp/keystore.password"); export STOREPASS
openssl pkcs12 -export -inkey "$tmp/key.pem" -in "$tmp/cert.pem" -certfile "$tmp/chain.pem" \
  -name dev-ndtp -passout "pass:$STOREPASS" -out "$tmp/new-keycloak.p12"
keytool -delete -storetype PKCS12 -keystore keycloak.keystore.jks -storepass:env STOREPASS -alias dev-ndtp
keytool -importkeystore -noprompt -srcstoretype PKCS12 -srckeystore new-keycloak.p12 \
  -srcstorepass:env STOREPASS -deststoretype PKCS12 -destkeystore keycloak.keystore.jks \
  -deststorepass:env STOREPASS -srcalias dev-ndtp -destalias dev-ndtp

# Both truststores use the fixed alias ca for *.dev.ndtp.co.uk.
STOREPASS=$(cat "$tmp/truststore.password"); export STOREPASS
for file in keycloak.truststore.jks management-node-keycloak.truststore.jks; do
  if [ "$file" = keycloak.truststore.jks ]; then
    jq -r '.data["keycloak.truststore.jks"]' "$tmp/keycloak-cert.json" | base64 -d > "$tmp/$file"
  else
    jq -r '.data["keycloak.truststore.jks"]' "$tmp/management-node-cert.json" | base64 -d > "$tmp/$file"
  fi
  keytool -delete -storetype PKCS12 -keystore "$file" -storepass:env STOREPASS -alias ca
  keytool -importcert -noprompt -storetype PKCS12 -keystore "$file" -storepass:env STOREPASS \
    -alias ca -file fullchain.pem
done
unset STOREPASS

# Patch only the five known fields; every other secret field remains unchanged.
patch() {
  secret=$1
  shift
  echo "Patching secret/$secret"
  kubectl create secret generic ignored --dry-run=client -o json "$@" | jq '{data: .data}' > "$tmp/patch.json"
  kubectl --context dev -n ia-management-node patch secret "$secret" --type merge --patch-file "$tmp/patch.json"
}

patch keycloak-certs-secret --from-file=tls.crt="$tmp/cert.pem" --from-file=tls.key="$tmp/key.pem"
patch keycloak-cert --from-file=keycloak.keystore.jks="$tmp/keycloak.keystore.jks" --from-file=keycloak.truststore.jks="$tmp/keycloak.truststore.jks"
patch management-node-cert --from-file=keycloak.truststore.jks="$tmp/management-node-keycloak.truststore.jks"

echo "Restarting statefulset/keycloak"
kubectl --context dev -n ia-management-node rollout restart statefulset/keycloak
echo "Restarting deployment/management-node-api"
kubectl --context dev -n ia-management-node rollout restart deployment/management-node-api
