# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

variable "realm_id" {
  description = "Target realm where the client will be created"
  type        = string
}

variable "client_id" {
  description = "OIDC client_id"
  type        = string
}

variable "name" {
  description = "Human-friendly name (defaults to client_id)"
  type        = string
  default     = null
}

variable "description" {
  type    = string
  default = null
}

variable "enabled" {
  type    = bool
  default = true
}

variable "access_type" {
  description = "CONFIDENTIAL | PUBLIC | BEARER-ONLY"
  type        = string
  default     = "CONFIDENTIAL"
}

variable "standard_flow_enabled" {
  # auth code
  type    = bool
  default = false
}

variable "implicit_flow_enabled" {
  type    = bool
  default = false
}

variable "direct_access_grants_enabled" {
  # ROPC
  type    = bool
  default = false
}

variable "service_accounts_enabled" {
  type    = bool
  default = true
}

variable "consent_required" {
  type    = bool
  default = false
}

variable "backchannel_logout_session_required" {
  type    = bool
  default = false
}
variable "backchannel_logout_url" {
  type    = string
  default = null
}

variable "custom_roles" {
  description = "List of custom client roles to create on this client"
  type = list(object({
    name        = string
    description = optional(string)
  }))
  default = []
}

variable "assign_roles_to_service_account" {
  description = "If true, assign custom + extra roles to the service account"
  type        = bool
  default     = true
}

# X.509 client authentication settings
variable "client_authenticator_type" {
  description = "Client authenticator type to use for this client (e.g., client-secret, client-x509)"
  type        = string
  default     = "client-x509"
}

variable "x509_subject_dn" {
  description = "Expected Subject DN for TLS client authentication. Supports regex when x509_allow_regex_pattern_comparison is true."
  type        = string
  default     = "(.*?)(?:$)"
}

variable "x509_allow_regex_pattern_comparison" {
  description = "Whether to allow regex pattern comparison for x509.subjectdn. Keycloak attribute: x509.allow.regex.pattern.comparison"
  type        = bool
  default     = true
}

# Client scopes to be attached to this client
variable "default_client_scopes" {
  description = "List of existing client scopes to attach as default scopes to this client"
  type        = list(string)
  default     = []
}

variable "optional_client_scopes" {
  description = "List of existing client scopes to attach as optional scopes to this client"
  type        = list(string)
  default     = []
}

# Roles (with container client reference) to assign to this client's service account user
variable "service_account_role_ids" {
  description = "List of roles to assign to the client's service account. Each item must contain the role name and the source client identifier (client_id string) that owns the role. The module will resolve it to a UUID."
  type = list(object({
    name        = string # role NAME
    from_client = string # source client_id (string, e.g., 'management-node' or another client_id)
  }))
  default = []
}

# Token settings
variable "client_access_token_lifespan_seconds" {
  description = "Access token lifespan for this client (in seconds). Default 30 minutes (1800)."
  type        = number
  default     = 1800
}

variable "organisation" {
  description = "Organisation key to put in the client's 'organisation' claim; must match organisation.organisation_key in the database (e.g. \"ENV\"). Null adds no claim."
  type        = string
  default     = null
}
