# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

# Policy for the Management Node's Vault token. See docs/VAULT_ACCESS.md.
# pki-int and default-role must match application.vault.pki-mount and application.vault.default-role.

# Certificate status (cert/:serial), and the CA (cert/ca, cert/ca_chain).
path "pki-int/cert/*" {
  capabilities = ["read"]
}

path "pki-int/certs/revoked" {
  capabilities = ["list"]
}

path "pki-int/revoke" {
  capabilities = ["update"]
}

# Federator CSRs, bootstrap certificates and the OCSP signer.
# not_after is denied: Vault does not check it against the role's max_ttl.
path "pki-int/sign/default-role" {
  capabilities = ["update"]
  denied_parameters = {
    "not_after" = []
  }
}

# The token has no default policy, so it needs these to renew itself.
path "auth/token/renew-self" {
  capabilities = ["update"]
}

path "auth/token/lookup-self" {
  capabilities = ["read"]
}
