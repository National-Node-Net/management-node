# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

# Runs as a Vault administrator, never as the Management Node.
provider "vault" {
  address          = var.vault_address
  token            = var.vault_token
  skip_child_token = true
}
