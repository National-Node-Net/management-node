# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

variable "keycloak_url" {
  description = "Keycloak base URL"
  type        = string
}

variable "keycloak_realm" {
  description = "Realm to manage (typically 'master' for administrative operations)"
  type        = string
}

variable "keycloak_client_id" {
  description = "Keycloak client ID used for authentication"
  type        = string
}

variable "keycloak_username" {
  description = "Admin username for Keycloak"
  type        = string
}

variable "keycloak_password" {
  description = "Admin password for Keycloak"
  type        = string
  sensitive   = true
}

variable "keycloak_client_timeout" {
  description = "Timeout in seconds for Keycloak provider client requests"
  type        = number
}

variable "management_realm_name" {
  description = "Name of the Keycloak realm to create/manage"
  type        = string
}

variable "client_access_token_lifespan_seconds" {
  description = "Access token lifespan for clients (in seconds). Default 30 minutes (1800)."
  type        = number
  default     = 1800
}

variable "federator_clients" {
  description = "List of federator clients to create with their own roles and mapped roles from other clients"
  type = list(object({
    client = string
    roles  = optional(list(string), [])
    # Organisation key for the client's "organisation" claim; must match
    # organisation.organisation_key in the database (e.g. "ENV").
    organisation = optional(string)
    mapped_client_roles = optional(list(object({
      client = string
      roles  = list(string)
    })), [])
  }))
  default = []
}

