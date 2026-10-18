# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

resource "keycloak_openid_client" "this" {
  realm_id                     = var.realm_id
  client_id                    = var.client_id
  name                         = coalesce(var.name, var.client_id)
  description                  = var.description
  enabled                      = var.enabled
  access_type                  = var.access_type
  standard_flow_enabled        = var.standard_flow_enabled
  implicit_flow_enabled        = var.implicit_flow_enabled
  direct_access_grants_enabled = var.direct_access_grants_enabled
  service_accounts_enabled     = var.service_accounts_enabled

  # Per-client JWT access token lifespan (seconds)
  access_token_lifespan = var.client_access_token_lifespan_seconds

  # Optional toggles most people like off in machine clients
  consent_required                    = var.consent_required
  backchannel_logout_session_required = var.backchannel_logout_session_required
  backchannel_logout_url              = var.backchannel_logout_url

  client_authenticator_type = var.client_authenticator_type

  extra_config = {
    "x509.subjectdn"                      = var.x509_subject_dn
    "x509.allow.regex.pattern.comparison" = tostring(var.x509_allow_regex_pattern_comparison)
  }
}

# Attach default client scopes if provided
resource "keycloak_openid_client_default_scopes" "this" {
  count          = length(var.default_client_scopes) > 0 ? 1 : 0
  realm_id       = var.realm_id
  client_id      = keycloak_openid_client.this.id
  default_scopes = var.default_client_scopes
}

# Attach optional client scopes if provided
resource "keycloak_openid_client_optional_scopes" "this" {
  count           = length(var.optional_client_scopes) > 0 ? 1 : 0
  realm_id        = var.realm_id
  client_id       = keycloak_openid_client.this.id
  optional_scopes = var.optional_client_scopes
}

# Custom client roles (scoped to this client)
resource "keycloak_role" "custom_roles" {
  for_each    = { for r in var.custom_roles : r.name => r }
  realm_id    = var.realm_id
  client_id   = keycloak_openid_client.this.id
  name        = each.value.name
  description = try(each.value.description, null)
}

# Resolve container client UUIDs for the provided roles
# Build a set of unique source client_ids (strings) we need to resolve
locals {
  role_source_clients = toset([for r in var.service_account_role_ids : r.from_client])
}

data "keycloak_openid_client" "role_containers" {
  for_each  = local.role_source_clients
  realm_id  = var.realm_id
  client_id = each.key
}

# Assign provided roles to this client's service account (if any provided)
resource "keycloak_openid_client_service_account_role" "service_account_roles" {
  # Use only input-derived, stable keys for for_each to avoid plan-time unknowns
  for_each = { for r in var.service_account_role_ids : "${r.from_client}:${r.name}" => r }

  realm_id = var.realm_id
  # IMPORTANT: this client_id must be the container (owner) of the role)
  # Resolve the container client's UUID here (arguments may be unknown at plan time, which is OK)
  client_id               = data.keycloak_openid_client.role_containers[each.value.from_client].id
  service_account_user_id = keycloak_openid_client.this.service_account_user_id

  # Provider expects 'role' to be the role NAME
  role = each.value.name
}

# Optionally assign custom roles defined on this client to its own service account
# This ensures roles are not only created under the client, but are also granted to the
# service account so that the corresponding audience is applied in tokens.
resource "keycloak_openid_client_service_account_role" "assign_custom_roles_to_sa" {
  for_each = var.assign_roles_to_service_account && var.service_accounts_enabled ? keycloak_role.custom_roles : {}

  realm_id                = var.realm_id
  client_id               = keycloak_openid_client.this.id
  service_account_user_id = keycloak_openid_client.this.service_account_user_id
  role                    = each.key
}



# The organisation this client acts for, as a hard-coded claim on its tokens.
#
# The Management Node reads it into the principal, and policy resolves the calling
# organisation's attributes by matching it against organisation.organisation_key - so the value
# must be the key as the database holds it ("ENV"), not the client id ("FEDERATOR_ENV"). A client
# without this claim is an unknown organisation, and every product rule refuses it.
resource "keycloak_openid_hardcoded_claim_protocol_mapper" "organisation" {
  count = var.organisation == null ? 0 : 1

  realm_id         = var.realm_id
  client_id        = keycloak_openid_client.this.id
  name             = "organisation"
  claim_name       = "organisation"
  claim_value      = var.organisation
  claim_value_type = "String"

  add_to_id_token     = true
  add_to_access_token = true
  add_to_userinfo     = true
}
