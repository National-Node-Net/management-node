# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

resource "vault_audit" "file" {
  count = var.enable_audit_device ? 1 : 0

  type = "file"
  options = {
    file_path = var.audit_file_path
  }
}
