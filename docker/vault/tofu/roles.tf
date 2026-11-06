# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

# Signs federator CSRs. The name must match application.vault.default-role.
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

# Issues the delegated OCSP signer certificate and nothing else.
resource "vault_pki_secret_backend_role" "ocsp_signer" {
  backend                            = vault_mount.pki.path
  name                               = "ocsp-signer"
  allow_any_name                     = false
  allowed_domains                    = [var.ocsp_signer_common_name]
  allow_bare_domains                 = true
  allow_subdomains                   = false
  allow_glob_domains                 = false
  allow_localhost                    = false
  allow_ip_sans                      = false
  use_csr_sans                       = false
  require_cn                         = true
  server_flag                        = false
  client_flag                        = false
  code_signing_flag                  = false
  email_protection_flag              = false
  basic_constraints_valid_for_non_ca = true
  key_type                           = "any"
  key_bits                           = 0
  key_usage                          = ["DigitalSignature"]
  ext_key_usage                      = ["OCSPSigning"]
  max_ttl                            = var.ocsp_signer_max_ttl_seconds
  not_before_duration                = "30s"
}
