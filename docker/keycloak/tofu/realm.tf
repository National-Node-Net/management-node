# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.


resource "keycloak_realm" "management-node" {
  realm        = var.management_realm_name
  display_name = "Management Node Realm"
  enabled      = true

  # Common login toggles
  login_with_email_allowed = false
  registration_allowed     = false
  reset_password_allowed   = false

  # Optional: configure password policy
  password_policy = "hashIterations(27500) and length(12) and digits(1) and specialChars(1)"
}

# Manage realm default roles so that built-in account roles are not assigned by default
resource "keycloak_default_roles" "management_node_defaults" {
  realm_id = keycloak_realm.management-node.id

  # Do not assign any realm-level default roles to new users
  # This effectively prevents Keycloak from granting the composite 'default-roles-<realm>'
  # which includes built-in client roles like 'manage-account' and 'view-profile'.
  default_roles = []
}
