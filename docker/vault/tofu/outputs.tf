# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

output "pki_intermediate_csr" {
  description = "CSR of the intermediate CA, for the offline root to sign"
  value       = vault_pki_secret_backend_intermediate_cert_request.ca.csr
}

output "management_node_role_id" {
  description = "VAULT_ROLE_ID for the Management Node"
  value       = vault_approle_auth_backend_role.management_node.role_id
}

output "management_node_secret_id" {
  description = "VAULT_SECRET_ID for the Management Node; null unless create_dev_secret_id is true"
  value       = one(vault_approle_auth_backend_role_secret_id.dev[*].secret_id)
  sensitive   = true
}
