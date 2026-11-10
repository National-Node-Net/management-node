# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

# Signs federator CSRs and the Management Node's OCSP signer. The name must match application.vault.default-role.
resource "vault_pki_secret_backend_role" "default_role" {
  backend            = vault_mount.pki.path
  name               = "default-role"
  allow_any_name     = true
  allow_subdomains   = true
  allow_glob_domains = true
  allow_ip_sans      = true
  server_flag        = true
  client_flag        = true
  key_type           = "any"
  key_bits           = 0
  max_ttl            = var.default_role_max_ttl_seconds
  allowed_other_sans = ["${var.bootstrap_oid};utf8:*"]
}
