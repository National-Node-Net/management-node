<!--
SPDX-License-Identifier: Apache-2.0
© Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
attributed to the Department for Business and Trade (UK) as the governing entity.
-->

# OPA (Policy Decision Point)

Runs [Open Policy Agent](https://www.openpolicyagent.org/) as the PDP this service
talks to, serving [`policy.rego`](policy.rego).

## Run

```bash
cd docker/opa
docker compose up -d
docker compose logs -f opa
```

| Port   | Purpose                                             |
|--------|-----------------------------------------------------|
| `8181` | Policy API — the decision endpoint the service calls |
| `8282` | Diagnostics only — `/health`, `/metrics`             |

Override with `OPA_PORT`, `OPA_DIAGNOSTIC_PORT` or `OPA_LOG_LEVEL`.

## How the service reaches it

Defaults in `application.yml` already line up with this compose file, so no
configuration change is needed for local runs:

| Property                        | Default                            |
|---------------------------------|------------------------------------|
| `application.opa.url`           | `http://localhost:8181`            |
| `application.opa.decision-path` | `/v1/data/management_node/decision` |

That path resolves to the `decision` rule of the `management_node` package in
`policy.rego`. If you rename the package or the rule, set `OPA_DECISION_PATH`
to match.

Every policy answers with the same document, read into
`DefaultPolicyDecisionOutput`:

```json
{
  "allow": true,
  "allowed_filtered_attributes": ["name", "topic"],
  "denied_filtered_attributes": ["internal_owner"],
  "masked_filtered_attributes": ["contact_email"]
}
```

A policy that decides only allow/deny still returns the three lists, empty.

Note `PolicyDecisionClient` **fails closed**: if OPA is unreachable, or returns
a result that cannot be read as that document, the decision is DENY. So the
container must be up before exercising any protected path
(`application.opa.protected-paths`, currently `/api/v1/configuration/**`).

In non-local environments the URL must be `https://` — the client applies the
configured keystore/truststore and enforces mTLS, which this local, plaintext
container does not terminate.

## The policy

`policy.rego` is currently a permissive placeholder (`default allow = true`).
Requests arrive as `{"input": {...}}` carrying the fields of `PolicyInput`:

| Field            | Whole-request PEP            | Product discover            |
|------------------|------------------------------|------------------------------|
| `clientId`       | calling client               | calling client               |
| `organisation`   | token `organisation` claim   | token `organisation` claim   |
| `organisationId` | id from client certificate   | id from client certificate   |
| `resource`       | request URI                  | `product:{id}`               |
| `action`         | HTTP method                  | `discover`                   |

The two organisation fields are distinct and independently sourced.
`organisation` is the access token's `organisation` claim (e.g.
`FEDERATOR_ENV`), or `unknown_organisation` when the token carries no such
claim. `organisationId` is the id of the `organisation` row resolved from the
client certificate by `CertificateValidationInterceptor`. A policy may read
either, or require both to agree.

Both are omitted when unknown, so a rule that reads one must tolerate its
absence.

## Query it directly

```bash
curl -s -X POST localhost:8181/v1/data/management_node/decision \
  -H 'Content-Type: application/json' \
  -d '{"input":{"subject":{"clientId":"MANAGEMENT_NODE_CLIENT","organisation":{"key":"FEDERATOR_ENV"}},"resource":{"kind":"product","id":"42"},"action":"discover"}}'
# => {"result":{"allow":true,"allowed_filtered_attributes":[],"denied_filtered_attributes":[],"masked_filtered_attributes":[]}}
```

## Reloading after an edit

The policy is bind-mounted read-only, so restart to pick up changes:

```bash
docker compose restart opa
```

Or push it in place, without a restart:

```bash
curl -X PUT localhost:8181/v1/policies/policy --data-binary @policy.rego
```

## Checks

```bash
docker compose exec opa /opa check /policies/policy.rego
docker compose exec opa /opa version
```
