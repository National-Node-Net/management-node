# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

resource "vault_auth_backend" "approle" {
  type = "approle"
  path = "approle"
}

resource "vault_approle_auth_backend_role" "management_node" {
  backend                 = vault_auth_backend.approle.path
  role_name               = "management-node"
  token_policies          = [vault_policy.mgmt_node_pki.name]
  token_no_default_policy = true
  token_type              = "service"
  token_ttl               = var.approle_token_ttl_seconds
  token_max_ttl           = var.approle_token_max_ttl_seconds
  token_bound_cidrs       = var.approle_token_bound_cidrs
  secret_id_bound_cidrs   = var.approle_secret_id_bound_cidrs
  secret_id_num_uses      = 0 # the service logs in again whenever a token reaches its maximum TTL
}

resource "vault_approle_auth_backend_role_secret_id" "dev" {
  count = var.create_dev_secret_id ? 1 : 0

  backend   = vault_auth_backend.approle.path
  role_name = vault_approle_auth_backend_role.management_node.role_name
}

# Gives the Management Node one named identity in the audit log.
resource "vault_identity_entity" "management_node" {
  name = "management-node"
}

resource "vault_identity_entity_alias" "management_node" {
  name           = vault_approle_auth_backend_role.management_node.role_id
  mount_accessor = vault_auth_backend.approle.accessor
  canonical_id   = vault_identity_entity.management_node.id
}
