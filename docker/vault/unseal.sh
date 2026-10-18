#!/bin/sh
#
# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
#

# Watches the Vault server and unseals it whenever it comes up sealed.
#
# Shamir keys have to be supplied by something, so this trades secrecy for
# convenience: it is for local development only. Real deployments should use
# auto-unseal (transit / cloud KMS) instead of keys sitting on disk.
#
# Keys are read from the environment (see vault-keys.env):
#   VAULT_UNSEAL_KEY_1 .. VAULT_UNSEAL_KEY_5 - at least `threshold` of them.
set -u

VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
export VAULT_ADDR
INTERVAL="${UNSEAL_INTERVAL:-5}"

keys() {
  for n in 1 2 3 4 5; do
    eval "key=\${VAULT_UNSEAL_KEY_$n:-}"
    [ -n "$key" ] && echo "$key"
  done
}

if [ -z "$(keys)" ]; then
  echo "unseal: no VAULT_UNSEAL_KEY_* set, nothing to do" >&2
  exit 1
fi

echo "unseal: watching $VAULT_ADDR every ${INTERVAL}s"
while true; do
  # vault status exits 0 = unsealed, 2 = sealed, anything else = unreachable
  vault status >/dev/null 2>&1
  case $? in
    0) ;;
    2)
      echo "unseal: vault is sealed, applying keys"
      keys | while read -r key; do
        vault operator unseal "$key" >/dev/null 2>&1
        vault status >/dev/null 2>&1 && break
      done
      vault status >/dev/null 2>&1 \
        && echo "unseal: vault unsealed" \
        || echo "unseal: still sealed after applying keys (wrong keys? below threshold?)" >&2
      ;;
    *) ;; # not up yet, or restarting
  esac
  sleep "$INTERVAL"
done
