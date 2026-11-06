#!/bin/sh
#
# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
#

# Checks an applied configuration from the outside, using the Management Node's own credentials.
# Requires the vault (or bao) CLI and these variables:
#   VAULT_ADDR, ADMIN_TOKEN, VAULT_ROLE_ID, VAULT_SECRET_ID
# Optional: PKI_MOUNT (default pki-int), KV_MOUNT (default secret), AUDIT_FILE (audit log on this machine),
#   VAULT_CLI (default vault), SIGNER_CN (default ocsp-signer.management-node),
#   SIGNER_KV_PATH (default management-node/ocsp-signer), SIGNER_CSR (a CSR for SIGNER_CN; generated with openssl if unset).
set -u

PKI_MOUNT="${PKI_MOUNT:-pki-int}"
KV_MOUNT="${KV_MOUNT:-secret}"
SIGNER_KV_PATH="${SIGNER_KV_PATH:-management-node/ocsp-signer}"
AUDIT_FILE="${AUDIT_FILE:-}"
CLI="${VAULT_CLI:-vault}"
SIGNER_CN="${SIGNER_CN:-ocsp-signer.management-node}"
SIGNER_CSR="${SIGNER_CSR:-}"
failures=0

pass() { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; failures=$((failures + 1)); }

# expect_allowed <description> <command...>
expect_allowed() {
  description=$1
  shift
  if output=$("$@" 2>&1); then pass "$description"; else fail "$description: $output"; fi
}

# expect_denied <description> <command...>
expect_denied() {
  description=$1
  shift
  if output=$("$@" 2>&1); then
    fail "$description: expected 403 but the call succeeded"
  else
    case "$output" in
      *"Code: 403"*) pass "$description" ;;
      *) fail "$description: expected 403, got: $output" ;;
    esac
  fi
}

expect_setting() {
  description=$1
  actual=$2
  case "$actual" in
    *"$3"*) pass "$description" ;;
    *) fail "$description: expected $3, got: $actual" ;;
  esac
}

as_admin() { VAULT_TOKEN="$ADMIN_TOKEN" "$CLI" "$@"; }

MN_TOKEN=$("$CLI" write -field=token auth/approle/login role_id="$VAULT_ROLE_ID" secret_id="$VAULT_SECRET_ID") \
  || { echo "FAIL - AppRole login"; exit 1; }
pass "AppRole login"
export VAULT_TOKEN="$MN_TOKEN"

policies=$("$CLI" token lookup -format=json)
expect_setting "token carries mgmt-node-pki" "$policies" "mgmt-node-pki"
case "$policies" in
  *'"default"'*) fail "token must not carry the default policy" ;;
  *) pass "token does not carry the default policy" ;;
esac
expect_allowed "token renews itself" "$CLI" token renew

csr="$SIGNER_CSR"
if [ -z "$csr" ]; then
  key=$(mktemp)
  csr=$(mktemp)
  openssl req -new -newkey rsa:2048 -nodes -keyout "$key" -subj "/CN=$SIGNER_CN" -out "$csr" 2>/dev/null
  rm -f "$key"
fi

# Issues a short-lived signer and revokes it again, so nothing usable is left behind.
signed=$("$CLI" write -format=json "$PKI_MOUNT/sign/ocsp-signer" csr=@"$csr" ttl=5m 2>&1)
serial=$(echo "$signed" | sed -n 's/.*"serial_number": "\([^"]*\)".*/\1/p')
if [ -n "$serial" ]; then
  pass "sign/ocsp-signer issues a certificate"
  expect_allowed "revoke" "$CLI" write "$PKI_MOUNT/revoke" serial_number="$serial"
  revoked=$("$CLI" list "$PKI_MOUNT/certs/revoked" 2>&1)
  expect_setting "certs/revoked lists the serial" "$revoked" "$serial"
  revocation_time=$("$CLI" read -field=revocation_time "$PKI_MOUNT/cert/$serial" 2>&1)
  case "$revocation_time" in
    0 | *[!0-9]*) fail "cert/:serial has no revocation time: $revocation_time" ;;
    *) pass "cert/:serial returns the revocation time" ;;
  esac
else
  fail "sign/ocsp-signer: $signed"
fi

expect_allowed "sign/default-role issues a certificate" "$CLI" write "$PKI_MOUNT/sign/default-role" csr=@"$csr" ttl=5m

# Vault does not check not_after against max_ttl, so the policy has to refuse it.
expect_denied "not_after is denied on sign/ocsp-signer" \
  "$CLI" write "$PKI_MOUNT/sign/ocsp-signer" csr=@"$csr" not_after=2099-01-01T00:00:00Z
expect_denied "not_after is denied on sign/default-role" \
  "$CLI" write "$PKI_MOUNT/sign/default-role" csr=@"$csr" not_after=2099-01-01T00:00:00Z

# The secret may not be stored yet, so only a 403 counts as a failure here.
case "$("$CLI" read "$KV_MOUNT/data/$SIGNER_KV_PATH" 2>&1)" in
  *"Code: 403"*) fail "the OCSP signer secret cannot be read" ;;
  *) pass "the OCSP signer secret can be read" ;;
esac
expect_denied "other secrets are denied" "$CLI" read "$KV_MOUNT/data/other"

expect_denied "root/generate is denied" "$CLI" write "$PKI_MOUNT/root/generate/internal" common_name=x
expect_denied "issue/default-role is denied" "$CLI" write "$PKI_MOUNT/issue/default-role" common_name=x
expect_denied "config/urls is denied" "$CLI" read "$PKI_MOUNT/config/urls"
expect_denied "sys/mounts is denied" "$CLI" read sys/mounts
expect_denied "sys/policies is denied" "$CLI" read sys/policies/acl/default

urls=$(as_admin read -format=json "$PKI_MOUNT/config/urls")
case "$urls" in
  *'/api/v1/certificate/ocsp'*) fail "AIA must not point at the JWT-protected endpoint (see docs/OCSP.md)" ;;
  *) pass "AIA does not point at the JWT-protected endpoint" ;;
esac

tidy=$(as_admin read -format=json "$PKI_MOUNT/config/auto-tidy")
expect_setting "auto-tidy is enabled" "$tidy" '"enabled": true'
expect_setting "auto-tidy safety buffer" "$tidy" '"safety_buffer": 259200'
expect_setting "auto-tidy cleans revocation records" "$tidy" '"tidy_revoked_certs": true'

crl=$(as_admin read -format=json "$PKI_MOUNT/config/crl")
expect_setting "CRL auto rebuild" "$crl" '"auto_rebuild": true'
expect_setting "built-in OCSP responder is off" "$crl" '"ocsp_disable": true'

if [ -z "$AUDIT_FILE" ]; then
  echo "skip - audit log: AUDIT_FILE is not set"
elif [ ! -r "$AUDIT_FILE" ]; then
  fail "audit log $AUDIT_FILE cannot be read"
elif grep "$PKI_MOUNT/sign/ocsp-signer" "$AUDIT_FILE" | grep -q '"role_name":"management-node"'; then
  pass "audit log names the Management Node"
else
  fail "audit log has no sign/ocsp-signer entry for management-node"
fi

if [ "$failures" -ne 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"
