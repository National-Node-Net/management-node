# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

variable "vault_address" {
  description = "Vault or OpenBao address"
  type        = string
}

variable "vault_token" {
  description = "Administrator token used to apply this configuration"
  type        = string
  sensitive   = true
}

variable "pki_mount" {
  description = "PKI mount of the federation CA; must match application.vault.pki-mount"
  type        = string
  default     = "pki-int"
}

variable "pki_max_lease_ttl_seconds" {
  description = "Maximum certificate lifetime on the PKI mount, in seconds"
  type        = number
  default     = 315360000 # 87600h
}

variable "pki_intermediate_common_name" {
  description = "Common name of the intermediate CA. Changing it generates a new private key."
  type        = string
  default     = "Management Node Intermediate CA"
}

variable "pki_intermediate_signed_chain" {
  description = "PEM chain returned by the offline root: the signed intermediate first, then the root. Null until the CSR has been signed; the mount cannot issue certificates before that."
  type        = string
  default     = null
}

variable "issuing_certificates" {
  description = "AIA CA Issuers URLs written into new certificates"
  type        = list(string)
}

variable "crl_distribution_points" {
  description = "CRL Distribution Point URLs written into new certificates"
  type        = list(string)
}

variable "crl_expiry" {
  description = "How long a generated CRL stays valid"
  type        = string
  default     = "72h"
}

variable "default_role_max_ttl_seconds" {
  description = "Maximum lifetime of certificates signed with default-role, in seconds"
  type        = number
  default     = 31536000 # 8760h
}

variable "bootstrap_oid" {
  description = "OID of the bootstrap certificate marker; must match application.bootstrap.oid"
  type        = string
  default     = "1.3.6.1.4.1.32473.1.1"
}

variable "tidy_interval_seconds" {
  description = "How often auto-tidy runs, in seconds"
  type        = number
  default     = 43200 # 12h
}

variable "tidy_safety_buffer_seconds" {
  description = "How long after expiry a certificate stays in storage, in seconds"
  type        = number
  default     = 259200 # 72h
}

variable "approle_token_ttl_seconds" {
  description = "Lifetime of a Management Node token before it must be renewed, in seconds"
  type        = number
  default     = 900 # 15m
}

variable "approle_token_max_ttl_seconds" {
  description = "Lifetime of a Management Node token including renewals, in seconds. The service then logs in again."
  type        = number
  default     = 14400 # 4h
}

variable "approle_token_bound_cidrs" {
  description = "Source addresses allowed to use Management Node tokens; empty means any"
  type        = list(string)
  default     = []
}

variable "approle_secret_id_bound_cidrs" {
  description = "Source addresses allowed to log in with the Management Node secret ID; empty means any"
  type        = list(string)
  default     = []
}

variable "create_dev_secret_id" {
  description = "Generate a secret ID and expose it as a sensitive output. Local development only: the value ends up in the state file."
  type        = bool
  default     = false
}

variable "enable_audit_device" {
  description = "Enable a file audit device. Set to false when the audit device is managed elsewhere, or declared in the server configuration."
  type        = bool
  default     = true
}

variable "audit_file_path" {
  description = "Audit log location inside the Vault server"
  type        = string
  default     = "/vault/logs/audit.log"
}
