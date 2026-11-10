# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

resource "vault_pki_secret_backend_config_urls" "urls" {
  backend                 = vault_mount.pki.path
  issuing_certificates    = var.issuing_certificates
  crl_distribution_points = var.crl_distribution_points
  # No OCSP URL while the endpoint needs a JWT: standard TLS clients would fail or skip the check, and a URL
  # cannot be removed from issued certificates. Federators use the revocation library instead. See docs/OCSP.md.
  ocsp_servers = []
}

resource "vault_pki_secret_backend_crl_config" "crl" {
  backend      = vault_mount.pki.path
  expiry       = var.crl_expiry
  auto_rebuild = true
  # The Management Node is the only OCSP responder.
  ocsp_disable = true
}

# Durations are in seconds because that is how Vault returns them, which keeps plans empty.
resource "vault_generic_endpoint" "auto_tidy" {
  path                 = "${vault_mount.pki.path}/config/auto-tidy"
  ignore_absent_fields = true
  disable_delete       = true

  data_json = jsonencode({
    enabled                               = true
    interval_duration                     = var.tidy_interval_seconds
    safety_buffer                         = var.tidy_safety_buffer_seconds
    tidy_cert_store                       = true
    tidy_revoked_certs                    = true
    tidy_revoked_cert_issuer_associations = true
  })
}
