<!--
SPDX-License-Identifier: Apache-2.0
© Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
attributed to the Department for Business and Trade (UK) as the governing entity.
-->

# OPA (Policy Decision Point)

Runs [Open Policy Agent](https://www.openpolicyagent.org/) as the PDP this service
talks to, serving the policy tree under [`policies/`](policies).

## Run

```bash
cd docker/opa
docker compose up -d
docker compose logs -f opa
```

| Port   | Purpose                                              |
|--------|------------------------------------------------------|
| `8181` | Policy API — the decision endpoint the service calls |
| `8282` | Diagnostics only — `/health`, `/metrics`             |

Override with `OPA_PORT`, `OPA_DIAGNOSTIC_PORT` or `OPA_LOG_LEVEL`.

## How the service reaches it

| Property                        | Default                      |
|---------------------------------|------------------------------|
| `application.opa.url`           | `http://localhost:8181`      |
| `application.opa.decision-path` | `/v1/data/dispatch/decision` |

Only controller methods annotated `@Policy(resource, action)` are sent here;
`input.resource.kind` and `input.action` are those two values. The service never
names a rule — the dispatcher picks it.

`PolicyDecisionClient` **fails closed**: if OPA is unreachable, or the answer
cannot be read, the decision is DENY. Keep the container up while exercising
annotated endpoints. In non-local environments the URL must be `https://`.

## Layout

```
policies/
  dispatch.rego                   package dispatch — the entrypoint
  lib/decision.rego               contract id, deny-shaped document, organisation helpers
  fallback.rego                   policies.fallback                — global fallback: deny
  configuration/fallback.rego     policies.configuration.fallback  — allow (local placeholder)
  product/fallback.rego           policies.product.fallback        — read-only for a known organisation
  product/discover.rego           policies.product.discover        — discovery, per request and per candidate
  product/subscribe.rego          policies.product.subscribe       — subscription and its terms
  routing/data.json               data.routing
  *_test.rego                     unit tests (ignored by the server)
```

The directory mirrors the package only for readability; OPA keys rules by
`package`, and data files by directory (`routing/data.json` → `data.routing`).

## The decision document

Every decision, whichever rule answered, has this shape:

```json
{
  "allow": true,
  "reasons": ["dispatch.resource_fallback"],
  "policy": {"id": "product.fallback", "version": "policies.product.fallback/1.0.0", "resolution": "resource_fallback"},
  "details": {"access_level": "read"}
}
```

`reasons` are sorted, stable codes. `policy` is provenance added by the
dispatcher — a rule cannot set it. `details` is rule-specific and is read into
the type named by `@Policy(details = ...)`. Attribute filtering
(`allowed_filtered_attributes`, `denied_filtered_attributes`,
`masked_filtered_attributes`) is not part of the envelope: a rule that needs it
returns the lists inside its `details`, as `product/discover.rego` does.

## How resolution works

For `(resource, action)` the dispatcher takes the first of:

| # | Resolution          | Looks for                                          | Extra reason                 |
|---|---------------------|----------------------------------------------------|------------------------------|
| 1 | `route`             | enabled `data.routing.routes` entry, lowest `priority` | —                        |
| 2 | `exact`             | `data.policies[resource][action]`                  | —                            |
| 3 | `resource_fallback` | `data.policies[resource].fallback`                 | `dispatch.resource_fallback` |
| 4 | `global_fallback`   | `data.policies.fallback`                           | `dispatch.global_fallback`   |

It moves to the next step **only when a module is absent**. Everything else
denies rather than trying a more general rule, because a fallback quietly
answering for a broken rule would hide the breakage:

| Situation                                          | Reason                          |
|----------------------------------------------------|---------------------------------|
| a route names a module that is not loaded          | `dispatch.route_policy_missing` |
| the selected module's `contract` is not `management-node.decision/1` (or missing) | `dispatch.contract_mismatch` |
| the selected module's `decision` is undefined or not an object | `dispatch.decision_undefined` |
| nothing resolves, not even the global fallback     | `dispatch.no_policy`            |

Only the contract's fields are copied from a rule's `decision`, each type-checked:
`allow` must be literally `true`, a non-list list becomes `[]`, non-object
`details` become `{}`. `fallback` is reserved as a resource or action name.

## Adding a rule for a new resource/action

1. Create `policies/<resource>/<action>.rego`:

   ```rego
   package policies.invoice.read

   import data.lib.decision.deny_shape
   import data.lib.decision.organisation_known

   contract := "management-node.decision/1"

   version := "policies.invoice.read/1.0.0"

   decision := object.union(deny_shape, {"allow": allow, "reasons": reasons, "details": {}})

   default allow := false

   allow if organisation_known

   reasons := sort([r | some r in reason_set])

   reason_set contains "organisation.missing" if not organisation_known
   ```

   Starting from `deny_shape` keeps the rule total: any field it does not set is
   still present, and denied. Omit `policy` — the dispatcher adds it.
2. Add `<action>_test.rego` beside it, then run the checks below.
3. Annotate the endpoint `@Policy(resource = "invoice", action = "read")`.

No dispatcher change is needed: it reaches the module by dynamic reference.
A `policies/<resource>/fallback.rego` answers every action of that resource
without its own rule.

## Adding a route

Routes let one rule serve several actions without Rego changes. Add an entry to
`policies/routing/data.json`:

```json
{"id": "route-product-list", "resource": "product", "action": "list",
 "policy": "product.fallback", "priority": 10, "enabled": true}
```

`policy` is `<resource>.<action>` of the module to use (or `fallback` for the
global one). Lowest `priority` wins, `id` breaks ties, `enabled: false` routes
are ignored. A route is final: if its module is missing the request is denied,
not passed on to exact or fallback resolution.

## Query it directly

Subscribe — exact rule, terms in `details`:

```bash
curl -s -X POST localhost:8181/v1/data/dispatch/decision -d '{"input":{
  "subject":{"organisation":{"key":"FEDERATOR_ENV","attributes":{"trusted_subscriber":true,"max_subscription_days":90}}},
  "resource":{"kind":"product"},"action":"subscribe",
  "request":{"method":"POST","body":{"productId":42,"scheduleType":"cron"}}}}'
# => {"result":{"allow":true, ..., "reasons":[],
#      "policy":{"id":"product.subscribe","resolution":"exact","version":"policies.product.subscribe/1.0.0"},
#      "details":{"max_validity_days":90,"permitted_schedule_types":["cron","interval"],"requires_approval":false}}}
```

View — no `product.view` rule, answered by the product fallback:

```bash
curl -s -X POST localhost:8181/v1/data/dispatch/decision -d '{"input":{
  "subject":{"organisation":{"key":"FEDERATOR_ENV"}},
  "resource":{"kind":"product","id":"42"},"action":"view","request":{"method":"GET"}}}'
# => {"result":{"allow":true, ..., "reasons":["dispatch.resource_fallback"],
#      "policy":{"id":"product.fallback","resolution":"resource_fallback",...},"details":{"access_level":"read"}}}
```

Unknown resource — global fallback, denied:

```bash
curl -s -X POST localhost:8181/v1/data/dispatch/decision -d '{"input":{
  "subject":{"organisation":{"key":"FEDERATOR_ENV"}},
  "resource":{"kind":"invoice"},"action":"read","request":{"method":"GET"}}}'
# => {"result":{"allow":false, ..., "reasons":["dispatch.global_fallback","policy.no_specific_rule"],
#      "policy":{"id":"fallback","resolution":"global_fallback","version":"policies.fallback/1.0.0"},"details":{}}}
```

## Reloading after an edit

The tree is bind-mounted read-only, so restart to pick up changes:

```bash
docker compose restart opa
```

## Checks

The image has no shell, so run the binary directly against the host tree:

```bash
P="$PWD/policies:/p:ro"
docker run --rm -v "$P" openpolicyagent/opa:1.20.2 check --strict /p
docker run --rm -v "$P" openpolicyagent/opa:1.20.2 test -v /p
docker run --rm -v "$P" openpolicyagent/opa:1.20.2 fmt --diff /p   # no output = formatted
```
