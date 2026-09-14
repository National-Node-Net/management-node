# Authentication Requirements

**Repository:** `management-node`  
**Description:** `Provides APIs to be accessed by Consumer and Producer Federators for the purpose of dynamic configuration management `  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0 `

---
Management Node uses OAuth 2.0 with JWT bearer tokens for authentication and authorization. Tokens are typically issued by Keycloak in this project’s reference setup.

Core requirements for every request to protected APIs:
- Bearer token: Requests must include `Authorization: Bearer <JWT>`.
- Audience (aud) claim: The token MUST contain an audience that includes `"management-node"`.
- resource_access claim: The token MUST include a `resource_access` claim, which carries client-application roles used for authorization decisions.

Notes:
- The API enforces role checks at endpoint level using Spring Security `@PreAuthorize` expressions.
- The Swagger UI documents the security scheme as HTTP bearer with JWT; you can use it to try endpoints by supplying a valid token.

## Token structure requirements

A compliant JWT will contain at least the following claims:
- `aud`: must include `management-node` (either as a string or within an array, depending on the issuer configuration).
- `resource_access`: an object mapping client IDs to role arrays.

Sample JWT payload (use this structure when testing locally):
```
{
  "exp": 1757863604,
  "iat": 1757861804,
  "jti": "trrtcc:a245819b-9a9f-648f-2e95-2390f6987c03",
  "iss": "https://localhost:8443/realms/mng-node",
  "aud": [
    "management-node",
    "FEDERATOR_HEG"
  ],
  "sub": "ec13a601-9b02-443a-99ff-66f1eb146ae9",
  "typ": "Bearer",
  "azp": "FEDERATOR_BCC",
  "resource_access": {
    "management-node": {
      "roles": [
        "access_producer_configurations",
        "access_consumer_configurations",
        "create_keys",
        "sign_certificate",
        "access_public_certificates",
        "request_bootstrap_certificate",
        "BrownfieldLandAvailability",
        "PendingPlanningApplications"
      ]
    }
  },
  "scope": "FEDERATOR_PRODUCER MANAGEMENT_NODE_ACCESS FEDERATOR_CONSUMER"
}
```

Notes:
- The aud claim may be a list (as shown) and must include "management-node".
- The resource_access.management-node.roles array must contain the role required for the API you are calling.

## Role requirements per API

- Producer API: Federator clients may access Producer configuration only when their token contains the role `access_producer_configurations` under the `resource_access` for the audience/client `management-node`.
  - Enforcement in code: `@PreAuthorize("hasAuthority('ROLE_management-node:access_producer_configurations')")` on `/api/v1/configuration/producer`.

- Consumer API: Federator clients may access Consumer configuration only when their token contains the role `access_consumer_configurations` under the `resource_access` for the audience/client `management-node`.
  - Enforcement in code: `@PreAuthorize("hasAuthority('ROLE_management-node:access_consumer_configurations')")` on `/api/v1/configuration/consumer`.

- Key Pair / CSR Creation API: Clients may create RSA key pairs and certificate signing requests when their token contains the role `create_keys`.
  - Enforcement in code: `@PreAuthorize("hasAuthority('ROLE_management-node:create_keys')")` on `GET /api/v1/certificate/keyPair` and `POST /api/v1/certificate/csr/create`.

- CSR Signing API: Clients may sign certificate signing requests when their token contains the role `sign_certificate`.
  - Enforcement in code: `@PreAuthorize("hasAuthority('ROLE_management-node:sign_certificate')")` on `POST /api/v1/certificate/csr/sign`.

- Intermediate Certificate API: Clients may retrieve the intermediate CA certificate when their token contains the role `access_public_certificates`.
  - Enforcement in code: `@PreAuthorize("hasAuthority('ROLE_management-node:access_public_certificates')")` on `GET /api/v1/certificate/intermediate`.

- Bootstrap Certificate API: The onboarding service account may request bootstrap certificate packages when its token contains the role `request_bootstrap_certificate`. The request body contains the target `organisationId` and a CSR. If no certificate record exists for the organisation, one is created automatically. This role is typically assigned only to the website backend service account, not to individual federator clients.
  - Enforcement in code: `@PreAuthorize("hasAuthority('ROLE_management-node:request_bootstrap_certificate')")` on `POST /api/v1/certificate/bootstrap`.

- Product Discovery API: Clients may discover the products they are authorised to see when their token contains the role `discover_products`. Even with the role, results are further filtered per-product by the PDP (see `docs/POLICY_ENFORCEMENT_TESTING.md`) - the role only gates access to the endpoint itself.
  - Enforcement in code: `@PreAuthorize("hasAuthority('ROLE_management-node:discover_products')")` on `POST /api/v1/product/discovery`.

## How this maps to Keycloak

- In Keycloak, roles are typically assigned to a client (here conceptually the `management-node` client) and appear in tokens under `resource_access["management-node"].roles`.
- Ensure the token’s audience includes `management-node`. This can be achieved by:
  - Setting the client as an audience in the token via an Audience mapper, or
  - Using the `audience resolve`/`Full Scope Allowed` as per your realm design.
- Create and assign the following client roles on the `management-node` client:
  - `access_producer_configurations`
  - `access_consumer_configurations`
  - `create_keys`
  - `sign_certificate`
  - `access_public_certificates`
  - `request_bootstrap_certificate`
  - `discover_products`
- Assign configuration roles to the appropriate Producer or Consumer Federator clients or service accounts.
- Assign certificate roles (`create_keys`, `sign_certificate`, `access_public_certificates`) to federator service accounts that manage their own certificates.
- Assign `request_bootstrap_certificate` only to the website/onboarding backend service account.

## Requesting tokens (example)

Using client credentials with mTLS (as per the project’s Keycloak setup):
```
curl --location 'https://localhost:8443/realms/mng-node/protocol/openid-connect/token' \
  --cert client.crt --key client.key \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'client_id=<federator-client-id>' \
  --data-urlencode 'grant_type=client_credentials'
```

Supply the returned access token to the Management Node API requests:
```
curl -k 'https://localhost:8090/api/v1/configuration/producer' \
  -H 'Authorization: Bearer <ACCESS_TOKEN>'
```

## Summary

- Authentication: JWT bearer tokens.
- Mandatory claims: `aud` includes `management-node`, and `resource_access` present.
- Authorization:
  - Producer API requires role: `access_producer_configurations`.
  - Consumer API requires role: `access_consumer_configurations`.
  - Key Pair / CSR Creation API requires role: `create_keys`.
  - CSR Signing API requires role: `sign_certificate`.
  - Intermediate Certificate API requires role: `access_public_certificates`.
  - Bootstrap Certificate API requires role: `request_bootstrap_certificate`.
  - Product Discovery API requires role: `discover_products` (plus per-product PDP authorisation).
- Swagger/OpenAPI: Use Swagger UI at `/swagger-ui.html` to explore and test with a valid token.