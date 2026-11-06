#!/usr/bin/env bash
#
# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the Department for Business and Trade (UK) as the governing entity.
#

# Issue a bootstrap certificate for an organisation and package it as a keystore and truststore,
# written as one JSON document (bootstrap.json).
#
# Flow (all calls over mTLS with the client certificate):
#   1. POST {IDP_URL}                                  client_credentials token
#   2. GET  {MN_URL}/api/v1/certificate/keyPair        RSA key pair       (role create_keys)
#   3. POST {MN_URL}/api/v1/certificate/csr/create     CSR from that pair (role create_keys)
#   4. POST {MN_URL}/api/v1/certificate/bootstrap      ZIP: certificate.pem + ca-chain.pem
#                                                      (role request_bootstrap_certificate)
#
# The bootstrap endpoint takes a CSR, not a key pair, so step 3 turns the key pair into one.
#
# Usage:
#   bash scripts/bootstrap_cert.sh [options]
#
# Options (each also settable by the environment variable in brackets):
#   --client-id <id>        OAuth client id                       [CLIENT_ID]       FEDERATOR_BCC
#   --client-secret <s>     client secret, if the client has one  [CLIENT_SECRET]   (none: mTLS client auth)
#   --org-id <id>           target organisationId                 [ORGANISATION_ID] 2
#   --cert <file>           client certificate for mTLS           [CLIENT_CERT]
#   --key <file>            client private key for mTLS           [CLIENT_KEY]
#   --cn <name>             CSR commonName                        [CSR_CN]          example.com
#   --org <name>            CSR organization (O)                  [CSR_O]           Example Corp
#   --ou <name>             CSR organizationalUnit (OU)           [CSR_OU]          IT
#   --country <cc>          CSR country (C)                       [CSR_C]           UK
#   --email <addr>          CSR email                             [CSR_EMAIL]       admin@example.com
#   --key-size <bits>       CSR keySize                           [CSR_KEY_SIZE]    2048
#   --key-usage <usage>     CSR keyUsage                          [CSR_KEY_USAGE]   digitalSignature
#   --san <dns>             CSR dnsSans entry, repeatable; the first one replaces the defaults
#                           [CSR_SANS, comma separated]  federator.internal.company.com,federator.company.com
#   --alias <name>          keystore alias of the key and certificate [KEY_ALIAS]    the client id
#   --keystore-password <p>   keystore.p12 password               [KEYSTORE_PASSWORD]   random
#   --truststore-password <p> truststore.p12 password             [TRUSTSTORE_PASSWORD] random
#   --out <dir>             output directory                      [OUT_DIR]         ./bootstrap-<client>-<timestamp>
#   --insecure              skip server certificate verification  [INSECURE=1]
#   -h, --help              show this help
#
# Output, in the output directory:
#   bootstrap.json          {createdAt, keystore.p12, keystore.password, truststore.p12,
#                           truststore.password, updatedAt}; the stores are base64 PKCS12
#   keystore.p12            private key + issued certificate + CA chain
#   truststore.p12          the CA chain, as trusted entries ca-0, ca-1, ...
#   bootstrap_bundle.zip    the ZIP returned by the bootstrap endpoint, and bootstrap_bundle/ unzipped
#   private-key.pem, public-key.pem, csr.pem
#
# Requires: curl, jq, unzip, openssl, and keytool (a JDK) or openssl 3.2+ for the truststore.

set -euo pipefail

IDP_URL="${IDP_URL:-https://ia-idp.dev.ndtp.co.uk/realms/mng-node/protocol/openid-connect/token}"
MN_URL="${MN_URL:-https://ia-management-node.dev.ndtp.co.uk:443}"
CERT_DIR_DEFAULT="/home/developer/IdeaProjects/ndtp-application-continuous-delivery/applications/environments/dev/ia-node/workload/core/5-management-node/idp/certs"

CLIENT_ID="${CLIENT_ID:-FEDERATOR_BCC}"
CLIENT_SECRET="${CLIENT_SECRET:-}"
ORGANISATION_ID="${ORGANISATION_ID:-2}"
CLIENT_CERT="${CLIENT_CERT:-$CERT_DIR_DEFAULT/client-org1.crt}"
CLIENT_KEY="${CLIENT_KEY:-$CERT_DIR_DEFAULT/client-org1.key}"
CSR_CN="${CSR_CN:-example.com}"
CSR_O="${CSR_O:-Example Corp}"
CSR_OU="${CSR_OU:-IT}"
CSR_C="${CSR_C:-UK}"
CSR_EMAIL="${CSR_EMAIL:-admin@example.com}"
CSR_KEY_SIZE="${CSR_KEY_SIZE:-2048}"
CSR_KEY_USAGE="${CSR_KEY_USAGE:-digitalSignature}"
CSR_SANS="${CSR_SANS-federator.internal.company.com,federator.company.com}"
SANS_FROM_FLAGS=0
KEY_ALIAS="${KEY_ALIAS:-}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
TRUSTSTORE_PASSWORD="${TRUSTSTORE_PASSWORD:-}"
OUT_DIR="${OUT_DIR:-}"
INSECURE="${INSECURE:-0}"

usage() { awk 'NR >= 8 && /^#/ { sub(/^# ?/, ""); print; next } NR > 8 { exit }' "$0"; }

die() {
  echo "ERROR: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --client-id) CLIENT_ID="${2:?}"; shift 2 ;;
    --client-secret) CLIENT_SECRET="${2:?}"; shift 2 ;;
    --org-id) ORGANISATION_ID="${2:?}"; shift 2 ;;
    --cert) CLIENT_CERT="${2:?}"; shift 2 ;;
    --key) CLIENT_KEY="${2:?}"; shift 2 ;;
    --cn) CSR_CN="${2:?}"; shift 2 ;;
    --org) CSR_O="${2:?}"; shift 2 ;;
    --ou) CSR_OU="${2:?}"; shift 2 ;;
    --country) CSR_C="${2:?}"; shift 2 ;;
    --email) CSR_EMAIL="${2:?}"; shift 2 ;;
    --key-size) CSR_KEY_SIZE="${2:?}"; shift 2 ;;
    --key-usage) CSR_KEY_USAGE="${2:?}"; shift 2 ;;
    --san)
      # The first --san replaces the default SANs; later ones add to it.
      if [[ "$SANS_FROM_FLAGS" == "0" ]]; then CSR_SANS=""; SANS_FROM_FLAGS=1; fi
      CSR_SANS="${CSR_SANS:+$CSR_SANS,}${2:?}"
      shift 2
      ;;
    --alias) KEY_ALIAS="${2:?}"; shift 2 ;;
    --keystore-password) KEYSTORE_PASSWORD="${2:?}"; shift 2 ;;
    --truststore-password) TRUSTSTORE_PASSWORD="${2:?}"; shift 2 ;;
    --out) OUT_DIR="${2:?}"; shift 2 ;;
    --insecure) INSECURE=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

for tool in curl jq unzip openssl; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required but not installed"
done
[[ -r "$CLIENT_CERT" ]] || die "client certificate not readable: $CLIENT_CERT"
[[ -r "$CLIENT_KEY" ]] || die "client key not readable: $CLIENT_KEY"
[[ "$ORGANISATION_ID" =~ ^[0-9]+$ ]] || die "organisationId must be numeric: $ORGANISATION_ID"
[[ "$CSR_KEY_SIZE" =~ ^[0-9]+$ ]] || die "keySize must be numeric: $CSR_KEY_SIZE"

KEY_ALIAS="${KEY_ALIAS:-$CLIENT_ID}"
OUT_DIR="${OUT_DIR:-./bootstrap-${CLIENT_ID}-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"
umask 077

CURL_OPTS=(--silent --show-error --cert "$CLIENT_CERT" --key "$CLIENT_KEY")
[[ "$INSECURE" == "1" ]] && CURL_OPTS+=(--insecure)

# call <out-file> <curl args...>: runs curl, writes the body to <out-file>, fails on a non-2xx status
# with the response body, so an error from the API is shown rather than parsed as data.
call() {
  local out="$1"
  shift
  local status
  status=$(curl "${CURL_OPTS[@]}" --output "$out" --write-out '%{http_code}' "$@") || die "request failed: curl $*"
  if [[ "$status" != 2* ]]; then
    echo "HTTP $status from: ${*: -1}" >&2
    cat "$out" >&2 || true
    echo >&2
    exit 1
  fi
}

# ---- 1. token ---------------------------------------------------------------------------------
echo "1/4 Requesting token for $CLIENT_ID ..."
TOKEN_ARGS=(--data-urlencode "grant_type=client_credentials" --data-urlencode "client_id=$CLIENT_ID")
[[ -n "$CLIENT_SECRET" ]] && TOKEN_ARGS+=(--data-urlencode "client_secret=$CLIENT_SECRET")
call "$OUT_DIR/token.json" -X POST -H 'Content-Type: application/x-www-form-urlencoded' "${TOKEN_ARGS[@]}" "$IDP_URL"
ACCESS_TOKEN=$(jq -er '.access_token' "$OUT_DIR/token.json") || die "no access_token in token response"

AUTH=(-H "Authorization: Bearer $ACCESS_TOKEN")

# ---- 2. key pair ------------------------------------------------------------------------------
echo "2/4 Creating key pair ..."
call "$OUT_DIR/keypair.json" -X GET "${AUTH[@]}" "$MN_URL/api/v1/certificate/keyPair"
jq -er '.privateKeyPem' "$OUT_DIR/keypair.json" >"$OUT_DIR/private-key.pem" || die "no privateKeyPem in key pair response"
jq -er '.publicKeyPem' "$OUT_DIR/keypair.json" >"$OUT_DIR/public-key.pem" || die "no publicKeyPem in key pair response"

# ---- 3. CSR -----------------------------------------------------------------------------------
echo "3/4 Creating CSR (CN=$CSR_CN) ..."
jq -n \
  --slurpfile kp "$OUT_DIR/keypair.json" \
  --arg cn "$CSR_CN" --arg o "$CSR_O" --arg ou "$CSR_OU" --arg c "$CSR_C" --arg sans "$CSR_SANS" \
  --arg email "$CSR_EMAIL" --argjson keySize "$CSR_KEY_SIZE" --arg keyUsage "$CSR_KEY_USAGE" \
  '{
     privateKeyPem: $kp[0].privateKeyPem,
     publicKeyPem: $kp[0].publicKeyPem,
     commonName: $cn,
     country: (if $c == "" then null else $c end),
     email: (if $email == "" then null else $email end),
     keySize: $keySize,
     keyUsage: (if $keyUsage == "" then null else $keyUsage end),
     organization: (if $o == "" then null else $o end),
     organizationalUnit: (if $ou == "" then null else $ou end),
     dnsSans: ($sans | split(",") | map(select(. != "")))
   }' >"$OUT_DIR/csr-request.json"
call "$OUT_DIR/csr.json" -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
  --data-binary "@$OUT_DIR/csr-request.json" "$MN_URL/api/v1/certificate/csr/create"
jq -er '.csrPem' "$OUT_DIR/csr.json" >"$OUT_DIR/csr.pem" || die "no csrPem in CSR response"

# ---- 4. bootstrap -----------------------------------------------------------------------------
echo "4/4 Requesting bootstrap certificate for organisation $ORGANISATION_ID ..."
jq -n --argjson org "$ORGANISATION_ID" --rawfile csr "$OUT_DIR/csr.pem" \
  '{organisationId: $org, csr: $csr}' >"$OUT_DIR/bootstrap-request.json"
call "$OUT_DIR/bootstrap_bundle.zip" -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
  -H 'Accept: application/zip' --data-binary "@$OUT_DIR/bootstrap-request.json" \
  "$MN_URL/api/v1/certificate/bootstrap"

# Unzip the bundle: certificate.pem and ca-chain.pem.
BUNDLE_DIR="$OUT_DIR/bootstrap_bundle"
rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR"
unzip -o -q "$OUT_DIR/bootstrap_bundle.zip" -d "$BUNDLE_DIR" || die "bootstrap response is not a valid ZIP"
CERT_PEM="$BUNDLE_DIR/certificate.pem"
[[ -s "$CERT_PEM" ]] || die "certificate.pem missing from bootstrap ZIP"
[[ -s "$BUNDLE_DIR/ca-chain.pem" ]] || die "ca-chain.pem missing from bootstrap ZIP"
# The server joins the chain's certificates with no separator, so one certificate's END line runs
# into the next one's BEGIN line, which openssl cannot read. Restore the line breaks.
CA_CHAIN_PEM="$OUT_DIR/ca-chain.pem"
sed 's/-----END CERTIFICATE-----/&\n/g' "$BUNDLE_DIR/ca-chain.pem" | sed '/^$/d' >"$CA_CHAIN_PEM"
openssl x509 -in "$CERT_PEM" -noout -pubkey | cmp -s - <(openssl pkey -in "$OUT_DIR/private-key.pem" -pubout) ||
  die "issued certificate does not match the generated private key"

# ---- stores -----------------------------------------------------------------------------------
echo "Building keystore.p12 and truststore.p12 ..."
random_password() { openssl rand -base64 32 | tr -d '/+=\n' | cut -c1-24; }
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-$(random_password)}"
TRUSTSTORE_PASSWORD="${TRUSTSTORE_PASSWORD:-$(random_password)}"
KEYSTORE="$OUT_DIR/keystore.p12"
TRUSTSTORE="$OUT_DIR/truststore.p12"

# Keystore: the private key, its certificate and the CA chain, under one alias.
openssl pkcs12 -export \
  -inkey "$OUT_DIR/private-key.pem" -in "$CERT_PEM" -certfile "$CA_CHAIN_PEM" \
  -name "$KEY_ALIAS" -out "$KEYSTORE" -passout "pass:$KEYSTORE_PASSWORD" ||
  die "could not build keystore.p12"

# Truststore: each CA certificate of the chain as a trusted entry, ca-0 (issuer of the certificate)
# upwards. Java only reads certificate-only PKCS12 entries that carry its trust attribute, which
# keytool writes; openssl writes it from 3.2 (-jdktrust), so it is the fallback.
CA_SPLIT_DIR="$OUT_DIR/ca-split"
rm -rf "$CA_SPLIT_DIR"
mkdir -p "$CA_SPLIT_DIR"
awk -v dir="$CA_SPLIT_DIR" '/-----BEGIN CERTIFICATE-----/ { n++ } n { print > (dir "/ca-" (n - 1) ".pem") }' "$CA_CHAIN_PEM"
rm -f "$TRUSTSTORE"
if command -v keytool >/dev/null 2>&1; then
  for ca in "$CA_SPLIT_DIR"/ca-*.pem; do
    keytool -importcert -noprompt -storetype PKCS12 -keystore "$TRUSTSTORE" -storepass "$TRUSTSTORE_PASSWORD" \
      -alias "$(basename "$ca" .pem)" -file "$ca" >/dev/null 2>&1 || die "could not add $ca to truststore.p12"
  done
elif openssl pkcs12 -help 2>&1 | grep -q -- '-jdktrust'; then
  openssl pkcs12 -export -nokeys -in "$CA_CHAIN_PEM" -jdktrust anyExtendedKeyUsage \
    -out "$TRUSTSTORE" -passout "pass:$TRUSTSTORE_PASSWORD" || die "could not build truststore.p12"
else
  die "building truststore.p12 needs keytool (a JDK) or openssl 3.2+"
fi
rm -rf "$CA_SPLIT_DIR"

# ---- JSON -------------------------------------------------------------------------------------
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
jq -n \
  --arg now "$NOW" \
  --arg keystore "$(base64 -w0 "$KEYSTORE")" \
  --arg keystorePassword "$KEYSTORE_PASSWORD" \
  --arg truststore "$(base64 -w0 "$TRUSTSTORE")" \
  --arg truststorePassword "$TRUSTSTORE_PASSWORD" \
  '{
     "createdAt": $now,
     "keystore.p12": $keystore,
     "keystore.password": $keystorePassword,
     "truststore.p12": $truststore,
     "truststore.password": $truststorePassword,
     "updatedAt": $now
   }' >"$OUT_DIR/bootstrap.json"

rm -f "$OUT_DIR/token.json" "$OUT_DIR/keypair.json" "$OUT_DIR/csr.json" \
  "$OUT_DIR/csr-request.json" "$OUT_DIR/bootstrap-request.json"

echo
echo "Done. Bootstrap certificate issued for organisation $ORGANISATION_ID."
echo "  subject : $(openssl x509 -in "$CERT_PEM" -noout -subject -nameopt RFC2253 | sed 's/^subject=//')"
echo "  serial  : $(openssl x509 -in "$CERT_PEM" -noout -serial | sed 's/^serial=//')"
echo "  expires : $(openssl x509 -in "$CERT_PEM" -noout -enddate | sed 's/^notAfter=//')"
echo "  JSON    : $OUT_DIR/bootstrap.json  (holds the keystore and its password; keep it private)"
