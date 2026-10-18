# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
terraform {
  required_version = ">= 1.6.0"
  required_providers {
    keycloak = {
      source  = "keycloak/keycloak"
      version = "~> 5.4"
    }
  }

  backend "s3" {}
  #backend "local" {}

}