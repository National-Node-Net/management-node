# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the Department for Business and Trade (UK) as the governing entity.

keycloak_url            = "http://localhost:8080"
keycloak_realm          = "master"
keycloak_client_id      = "admin-cli"
keycloak_username       = "admin"
keycloak_password       = "password"
keycloak_client_timeout = 30

management_realm_name = "mng-node"

# JWT access token lifespan for clients (in seconds). Default is 1800 (30 minutes)
client_access_token_lifespan_seconds = 1800

# Structured federator clients configuration
federator_clients = [

  {
    client       = "FEDERATOR_ENV"
    organisation = "ENV"
    roles        = ["FloodRiskMapZones"]
    mapped_client_roles = [
      {
        client = "management-node"
        roles  = ["access_producer_configurations", "access_consumer_configurations", "sign_certificate", "access_public_certificates", "create_keys", "product_discovery", "product_view", "product_subscribe"]
      }
    ]
  },
  {
    client       = "FEDERATOR_BCC"
    organisation = "BCC"
    roles        = ["PendingPlanningApplications"]
    mapped_client_roles = [
      {
        client = "management-node"
        roles  = ["access_producer_configurations", "access_consumer_configurations", "sign_certificate", "access_public_certificates", "create_keys", "product_discovery", "product_view", "product_subscribe"]
      }
    ]
  },
  {
    client       = "FEDERATOR_HEG"
    organisation = "HEG"
    roles        = ["BrownfieldLandAvailability"]
    mapped_client_roles = [
      {
        client = "management-node"
        roles  = ["access_producer_configurations", "access_consumer_configurations", "sign_certificate", "access_public_certificates", "create_keys", "product_discovery", "product_view", "product_subscribe"]
      }
    ]
  },
  {
    client = "MANAGEMENT_NODE_CLIENT"
    roles  = []
    mapped_client_roles = [
      {
        client = "management-node"
        roles  = ["access_producer_configurations", "access_consumer_configurations", "sign_certificate", "access_public_certificates", "create_keys", "product_discovery", "product_view", "product_subscribe"]
      }
    ]
  }
]

