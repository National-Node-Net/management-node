# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

resource "vault_policy" "mgmt_node_pki" {
  name = "mgmt-node-pki"
  policy = templatefile("${path.module}/policies/mgmt-node-pki.hcl.tftpl", {
    pki_mount = vault_mount.pki.path
  })
}
