# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.


output "client_uuid" {
  description = "UUID of the created Keycloak client (container client_id for role assignments)"
  value       = keycloak_openid_client.this.id
}

output "custom_role_names" {
  description = "List of custom role names created under this client (if any)"
  value       = keys(keycloak_role.custom_roles)
}