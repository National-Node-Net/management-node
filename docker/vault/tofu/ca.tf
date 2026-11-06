# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

# The private key is generated inside Vault and cannot be exported, so nothing secret reaches the state file.
# Only the CSR leaves Vault. Replacing this resource generates a new key.
resource "vault_pki_secret_backend_intermediate_cert_request" "ca" {
  backend     = vault_mount.pki.path
  type        = "internal"
  common_name = var.pki_intermediate_common_name
  key_type    = "rsa"
  key_bits    = 4096
}

# Second apply, once the offline root has signed the CSR. See the CA section of README.md.
resource "vault_pki_secret_backend_intermediate_set_signed" "ca" {
  count = var.pki_intermediate_signed_chain != null ? 1 : 0

  backend     = vault_mount.pki.path
  certificate = var.pki_intermediate_signed_chain

  depends_on = [vault_pki_secret_backend_intermediate_cert_request.ca]
}
