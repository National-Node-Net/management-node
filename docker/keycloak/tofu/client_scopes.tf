# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

# Defines custom OpenID client scopes for the Management Node realm
# These scopes are referenced by modules/clients via their names

resource "keycloak_openid_client_scope" "federator_consumer" {
  realm_id    = keycloak_realm.management-node.id
  name        = "FEDERATOR_CONSUMER"
  description = "Client scope for Federator consumer"
}

# Ensure Federator Consumer scope resolves audience dynamically (provider v5.4 compatible)
resource "keycloak_generic_protocol_mapper" "federator_consumer_audience_resolve" {
  realm_id        = keycloak_realm.management-node.id
  client_scope_id = keycloak_openid_client_scope.federator_consumer.id
  name            = "audience resolve"
  protocol        = "openid-connect"
  protocol_mapper = "oidc-audience-resolve-mapper"

  config = {
    "access.token.claim" = "true"
    "id.token.claim"     = "false"
  }
}

resource "keycloak_openid_client_scope" "federator_producer" {
  realm_id    = keycloak_realm.management-node.id
  name        = "FEDERATOR_PRODUCER"
  description = "Client scope for Federator producer"
}

# Ensure Federator Producer scope resolves audience dynamically (provider v5.4 compatible)
resource "keycloak_generic_protocol_mapper" "federator_producer_audience_resolve" {
  realm_id        = keycloak_realm.management-node.id
  client_scope_id = keycloak_openid_client_scope.federator_producer.id
  name            = "audience resolve"
  protocol        = "openid-connect"
  protocol_mapper = "oidc-audience-resolve-mapper"

  config = {
    "access.token.claim" = "true"
    "id.token.claim"     = "false"
  }
}

# Scope that adds management-node audience and exposes its client roles in tokens
resource "keycloak_openid_client_scope" "management_node_access" {
  realm_id    = keycloak_realm.management-node.id
  name        = "MANAGEMENT_NODE_ACCESS"
  description = "Adds management-node audience and maps its client roles"
}

# Add audience mapper to include management-node in the 'aud' claim for tokens using this scope
resource "keycloak_openid_audience_protocol_mapper" "management_node_aud" {
  realm_id        = keycloak_realm.management-node.id
  client_scope_id = keycloak_openid_client_scope.management_node_access.id
  name            = "aud-management-node"

  included_client_audience = "management-node"

  add_to_access_token = true
  add_to_id_token     = false
}

# Map client roles from management-node into resource_access.management-node.roles
resource "keycloak_openid_user_client_role_protocol_mapper" "management_node_roles" {
  realm_id        = keycloak_realm.management-node.id
  client_scope_id = keycloak_openid_client_scope.management_node_access.id
  name            = "roles-management-node"

  # client whose roles will be added to the token

  claim_name          = "resource_access.management-node.roles"
  add_to_access_token = true
  add_to_id_token     = false
  multivalued         = true
}
