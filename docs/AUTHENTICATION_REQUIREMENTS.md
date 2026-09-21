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
        "product_subscribe",
        "product_view",
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

Every role below is a client role on `management-node`, so it reaches the token as
`resource_access["management-node"].roles` and is enforced as the authority
`ROLE_management-node:<role>`. Where an endpoint carries `@Policy`, the resource and action
in that annotation select the rule that answers, never the URL, so renaming a path or
versioning the API does not change which rule decides (see
[Policy Enforcement](POLICY_ENFORCEMENT.md#dispatch)).

| API path | Required role | Policy rule | Policy resource | Action |
|---|---|---|---|---|
| `GET /api/v1/configuration/producer` | `access_producer_configurations` | `policies.configuration.fallback` (resource fallback) | `configuration` | `producer` |
| `GET /api/v1/configuration/consumer` | `access_consumer_configurations` | `policies.configuration.fallback` (resource fallback) | `configuration` | `consumer` |
| `GET /api/v1/certificate/keyPair` | `create_keys` | not annotated | n/a | n/a |
| `POST /api/v1/certificate/csr/create` | `create_keys` | not annotated | n/a | n/a |
| `POST /api/v1/certificate/csr/sign` | `sign_certificate` | not annotated | n/a | n/a |
| `GET /api/v1/certificate/intermediate` | `access_public_certificates` | not annotated | n/a | n/a |
| `POST /api/v1/certificate/bootstrap` | `request_bootstrap_certificate` | not annotated | n/a | n/a |
| `POST /api/v1/product/discover` | `product_discovery` | `policies.product.discover` (exact) | `product` | `discover` |
| `POST /api/v1/product/subscribe` | `product_subscribe` | `policies.product.subscribe` (exact) | `product` | `subscribe` |
| `GET /api/v1/product/{productId}` | `product_view` | `policies.product.view` (exact) | `product` | `view` |

Notes the table cannot carry:

- **Holding the role is necessary but not sufficient** on an annotated endpoint: a request that
  passes the role check can still be refused `403` by policy. Policy is only evaluated when
  `application.opa.enabled=true`; see [Policy Enforcement](POLICY_ENFORCEMENT.md).
- **Configuration endpoints** have no dedicated rule. `policies.configuration.fallback` allows,
  and is a placeholder: who may call them is gated by the role and the client certificate
  (`CertificateValidationInterceptor`, which applies to `/api/v1/configuration/**` only). It is
  the first thing to replace with real rules.
- **Certificate endpoints** are not annotated, so no decision is taken and the PDP is never
  called for them, so only authentication and the role check apply.
- **`request_bootstrap_certificate`** is for the website/onboarding backend service account, not
  individual federator clients. The request body carries the target `organisationId` and a CSR;
  if no certificate record exists for that organisation, one is created automatically.
- **Discovery's decision is the query.** `product.discover` returns a *search contract*, saying which
  products exist for the caller, what they may filter, sort and text-search on, and what is
  withheld from each result, and the service compiles that contract into the SQL.
- **View is discovery constrained to one product.** `product.view` applies the same caller gates
  as `product.discover` (a known organisation, a UK jurisdiction, some clearance) and returns the
  same contract, so a product the caller could not discover cannot be reached by its id either. A
  product policy excludes is answered `404`, exactly as one that does not exist.
- **Subscription terms come from policy**, not from Java: `product.subscribe` decides whether
  approval is required, the maximum validity, and the permitted schedule types.

## How this maps to Keycloak

- In Keycloak, roles are typically assigned to a client (here conceptually the `management-node` client) and appear in tokens under `resource_access["management-node"].roles`.
- Tokens must also carry an `organisation` claim whose value is the organisation's key **as the database holds it** (`ENV`, `HEG`, `BCC`), not the client id (`FEDERATOR_ENV`). Policy resolves the calling organisation's attributes by matching it against `organisation.organisation_key`; a token without it is an unknown organisation, and the product rules refuse it with `organisation.missing`. The sample federator clients get it from a hardcoded claim mapper in `docker/keycloak/tofu`.
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
  - `product_discovery`
  - `product_subscribe`
  - `product_view`
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
  - Product Discovery API requires role: `product_discovery` (plus policy, which decides which products are returned and what is shown of each; see [Policy Enforcement](POLICY_ENFORCEMENT.md)).
  - Product Subscription API requires role: `product_subscribe` (plus policy, which also sets the subscription terms).
  - Product View API requires role: `product_view` (plus policy, which decides whether this product exists for the caller and what is shown of it; see [Policy Enforcement](POLICY_ENFORCEMENT.md)).
- Swagger/OpenAPI: Use Swagger UI at `/swagger-ui.html` to explore and test with a valid token.