# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

resource "vault_mount" "pki" {
  path                  = var.pki_mount
  type                  = "pki"
  description           = "Federation CA"
  max_lease_ttl_seconds = var.pki_max_lease_ttl_seconds

  # Audit logs HMAC values by default; these are not secret and are needed to trace a revocation.
  audit_non_hmac_request_keys  = ["serial_number", "common_name"]
  audit_non_hmac_response_keys = ["serial_number", "revocation_time"]
}
