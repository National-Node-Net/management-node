#!/usr/bin/env bash
#
# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
#

# Helpers for the certificate revocation demo. Load them in Git Bash from the repository root:
#   source scripts/revocation-demo/demo-env.sh
#
# During the Postman demo only two are run by hand: node_csr (before "1.2 Sign CSR") and node_files (after it).
# The others are used by setup-vault.sh, start-app.sh and those two, or are there for checks from the terminal.
#
# Why openssl instead of curl: Git Bash's curl uses Windows Schannel, which cannot present a client certificate
# here. Keycloak (8443) and Management Node (8090) both require mTLS, so every HTTPS call goes through
# `openssl s_client` with a hand-written HTTP/1.0 request.

export ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export DEMO="$ROOT/scripts/revocation-demo"
export WORK="$DEMO/work"                              # every file the demo creates lands here (gitignored)
mkdir -p "$WORK"

export VAULT_CONTAINER=${VAULT_CONTAINER:-vault-demo}   # throwaway Vault in dev mode, created by setup-vault.sh
export VAULT_PORT=${VAULT_PORT:-8210}                   # not 8200, so it does not clash with docker/vault
export VAULT_TOKEN=demo-root-token                      # root token of that dev-mode container only
export MN_PORT=${MN_PORT:-8090}                         # Management Node API (mTLS)
export MN_ACTUATOR=${MN_ACTUATOR:-8081}                 # Management Node actuator (plain HTTP)
export KC_PORT=8443                                     # Keycloak (mTLS)
export OPERATOR_CERT="$ROOT/client"                     # client.crt / client.key from the README's certificate setup
export ORG_KEY=ENV                                      # the sample organisation the demo works on (client FEDERATOR_ENV)

# keytool: from JAVA_HOME, else from PATH, else from an installed JDK 21
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then KEYTOOL="$JAVA_HOME/bin/keytool"
elif command -v keytool > /dev/null 2>&1; then KEYTOOL=keytool
else KEYTOOL="$(ls -d "/c/Program Files/Java/jdk-21"*/bin/keytool.exe 2>/dev/null | head -1)"; fi
export KEYTOOL

# vault ARGS...  -> the Vault CLI inside the test Vault container
vault() { MSYS_NO_PATHCONV=1 docker exec -i -e VAULT_TOKEN="$VAULT_TOKEN" -e VAULT_ADDR=http://127.0.0.1:8200 "$VAULT_CONTAINER" vault "$@"; }

# db SQL  -> run SQL against the local Postgres (database keycloak_db, schema mn) and print the table
db() {
  ( set -a; . "$ROOT/docker/keycloak/.env"; set +a
    docker exec keycloak-postgres-1 psql -U "$POSTGRES_USER" -d keycloak_db -c "$1" )
}

# db_value SQL  -> the same, but print only the bare value(s), no table
db_value() {
  ( set -a; . "$ROOT/docker/keycloak/.env"; set +a
    docker exec keycloak-postgres-1 psql -U "$POSTGRES_USER" -d keycloak_db -At -c "$1" ) | tr -d '\r'
}

# mtls PORT METHOD PATH CERT_PREFIX [CONTENT_TYPE BODY_FILE]
#   Sends one HTTPS request with client certificate CERT_PREFIX.crt/.key and, when $TOKEN is set,
#   "Authorization: Bearer $TOKEN". Prints the raw HTTP answer (status line, headers, body).
#   HTTP/1.0 so the server answers with a plain body (no chunking) and closes the connection.
#   The Host header carries the port, as Postman sends it: Keycloak copies it into the token's iss claim,
#   and the application only accepts https://localhost:8443/realms/mng-node.
mtls() {
  local port=$1 method=$2 path=$3 cert=$4 ctype=$5 body=$6
  {
    printf '%s %s HTTP/1.0\r\nHost: localhost:%s\r\n' "$method" "$path" "$port"
    [ -n "$TOKEN" ] && printf 'Authorization: Bearer %s\r\n' "$TOKEN"
    if [ -n "$body" ]; then
      printf 'Content-Type: %s\r\nContent-Length: %s\r\n' "$ctype" "$(wc -c < "$body" | tr -d ' ')"
    fi
    printf '\r\n'
    [ -n "$body" ] && cat "$body"
  } | openssl s_client -connect "localhost:$port" -servername localhost \
        -cert "$cert.crt" -key "$cert.key" -quiet 2>/dev/null
}

# body  -> keep only the body of an HTTP answer read from stdin
body() { tr -d '\r' | awk '/^$/{b=1;next} b{print}'; }

# json FIELD  -> value of a string field in JSON from stdin (no jq needed), \n unescaped
json() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -1 | sed 's/\\n/\n/g'; }

# json_pem FILE  -> a PEM file as one JSON string value: CRs dropped, line breaks written as \n
json_pem() { awk '{ sub(/\r$/, ""); printf "%s\\n", $0 }' "$1"; }

# kc_secret CLIENT_ID  -> client secret of a Keycloak client, read through the admin API
#   (admin user and password from docker/keycloak/.env)
kc_secret() {
  local admin uuid
  ( set -a; . "$ROOT/docker/keycloak/.env"; set +a
    printf 'grant_type=password&client_id=admin-cli&username=%s&password=%s' "$KEYCLOAK_ADMIN" "$KEYCLOAK_ADMIN_PASSWORD" > "$WORK/.admin.form"
    admin=$(TOKEN= mtls "$KC_PORT" POST /realms/master/protocol/openid-connect/token "$OPERATOR_CERT" \
              application/x-www-form-urlencoded "$WORK/.admin.form" | body | json access_token)
    rm -f "$WORK/.admin.form"
    uuid=$(TOKEN=$admin mtls "$KC_PORT" GET "/admin/realms/mng-node/clients?clientId=$1" "$OPERATOR_CERT" | body \
             | sed -n 's/^\[{"id":"\([^"]*\)".*/\1/p')
    TOKEN=$admin mtls "$KC_PORT" GET "/admin/realms/mng-node/clients/$uuid/client-secret" "$OPERATOR_CERT" | body | json value )
}

# kc_token CLIENT_ID SECRET  -> access token from the client-credentials grant
kc_token() {
  printf 'grant_type=client_credentials&client_id=%s&client_secret=%s' "$1" "$2" > "$WORK/.token.form"
  TOKEN= mtls "$KC_PORT" POST /realms/mng-node/protocol/openid-connect/token "$OPERATOR_CERT" \
    application/x-www-form-urlencoded "$WORK/.token.form" | body | json access_token
  rm -f "$WORK/.token.form"
}

# jwt_claims TOKEN  -> decoded payload of a JWT (to show its roles and organisation)
jwt_claims() {
  local p; p=$(echo "$1" | cut -d. -f2 | tr '_-' '/+')
  while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done
  echo "$p" | base64 -d 2>/dev/null; echo
}

# api METHOD PATH [JSON_FILE] [CERT_PREFIX]  -> call Management Node, print the status line and body
#   Acts as the ENV node: uses $TOKEN if set, else fetches a fresh FEDERATOR_ENV token from Keycloak.
#   CERT_PREFIX defaults to the operator certificate; pass "$WORK/node" to call as the node.
api() {
  local cert=${4:-$OPERATOR_CERT}
  local token=${TOKEN:-$(kc_token FEDERATOR_ENV "$(kc_secret FEDERATOR_ENV)")}
  TOKEN=$token mtls "$MN_PORT" "$1" "$2" "$cert" application/json "$3" | tr -d '\r' | awk 'NR==1{print} /^$/{b=1;next} b{print}'
  echo
}

# ocsp_check CERT_FILE  -> ask Management Node's OCSP responder for the status of CERT_FILE, decoded by openssl
#   Acts as another federation participant: the management-node client, which has check_certificate_status.
ocsp_check() {
  local token len; token=$(kc_token management-node "$(kc_secret management-node)")
  openssl ocsp -issuer "$WORK/ca.pem" -cert "$1" -reqout "$WORK/.ocsp-check-req.der" -no_nonce > /dev/null 2>&1
  TOKEN=$token mtls "$MN_PORT" POST /api/v1/certificate/ocsp "$OPERATOR_CERT" application/ocsp-request "$WORK/.ocsp-check-req.der" > "$WORK/.ocsp-raw.bin"
  head -1 "$WORK/.ocsp-raw.bin" | tr -d '\r'
  len=$(tr -d '\r' < "$WORK/.ocsp-raw.bin" | sed -n 's/^Content-Length: //p' | head -1)
  tail -c "$len" "$WORK/.ocsp-raw.bin" > "$WORK/.ocsp-resp.der"   # the body is binary DER: the last Content-Length bytes
  openssl ocsp -respin "$WORK/.ocsp-resp.der" -resp_text -noverify 2>/dev/null \
    | grep -E 'OCSP Response Status|Cert Status|Revocation Time|Revocation Reason|This Update|Next Update' \
    | sed 's/^ */  /'
  rm -f "$WORK/.ocsp-check-req.der" "$WORK/.ocsp-raw.bin" "$WORK/.ocsp-resp.der"
}

# node_csr  -> step 1a: the node's own key and CSR, plus sign.json, the body of Postman request "1.2 Sign CSR"
#   The private key (node.key) never leaves this machine; only the CSR is sent.
#   sign.json is also copied to the clipboard when clip.exe (Windows) or pbcopy (macOS) is there.
node_csr() {
  ( cd "$WORK" && MSYS_NO_PATHCONV=1 openssl req -new -newkey rsa:2048 -nodes -keyout node.key -out node.csr \
      -subj "/CN=env-node.federator.local" 2>/dev/null \
    && printf '{\n  "csr": "%s"\n}\n' "$(json_pem node.csr)" > sign.json \
    && echo "node.key, node.csr, sign.json written to $WORK" ) || return 1
  if command -v clip.exe > /dev/null 2>&1; then clip.exe < "$WORK/sign.json"
  elif command -v pbcopy > /dev/null 2>&1; then pbcopy < "$WORK/sign.json"
  else echo "no clipboard tool: copy the body from $WORK/sign.json"; return 0; fi
  echo "sign.json copied to the clipboard: paste it as the body of Postman request 1.2 Sign CSR"
}

# node_files  -> step 1e, after "1.2 Sign CSR": the signed certificate as node.crt, and ocsp-req.der for the OCSP requests
#   Takes the serial Management Node stored for the organisation and reads that certificate from the test Vault.
#   Every 1.2 issues a new certificate, so run this again after each 1.2.
node_files() {
  local stored colon
  stored=$(db_value "select oc.serial_number from mn.organisation_certificate oc
                     join mn.organisation o on o.id = oc.organisation_id where o.organisation_key = '$ORG_KEY'
                     order by oc.issued_at desc limit 1")
  [ $(( ${#stored} % 2 )) -eq 1 ] && stored="0$stored"   # the database drops a leading zero (0b:57:... is stored as b57...)
  colon=$(echo "$stored" | sed 's/../&:/g; s/:$//')
  [ -n "$colon" ] || { echo "no certificate stored for organisation $ORG_KEY: send 1.2 Sign CSR first"; return 1; }
  rm -f "$WORK/node.crt" "$WORK/ocsp-req.der"
  vault read -field=certificate "pki-int/cert/$colon" | tr -d '\r' > "$WORK/node.crt"
  [ -s "$WORK/node.crt" ] || { echo "serial $colon not found in Vault: was 1.2 sent to the application started by start-app.sh?"; return 1; }
  openssl ocsp -issuer "$WORK/ca.pem" -cert "$WORK/node.crt" -reqout "$WORK/ocsp-req.der" -no_nonce > /dev/null 2>&1
  echo "serial $colon -> node.crt, ocsp-req.der written to $WORK"
  openssl x509 -in "$WORK/node.crt" -noout -subject -issuer -serial
}

echo "demo-env loaded: ROOT=$ROOT, WORK=$WORK"
