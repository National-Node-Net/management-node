# Policy Enforcement

**Repository:** `management-node`  
**Description:** `Provides APIs to be accessed by Consumer and Producer Federators for the purpose of dynamic configuration management `  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0 `

---

This document describes how the Management Node asks the Policy Decision Point (OPA) for authorisation decisions: the two enforcement points, the shape of the `input` document sent to the PDP, and where each field in it comes from.

## Configuration reference

Every key the Policy Enforcement Point reads, with the default from `application.yml`. The yaml
carries one-line comments only; this table is the reference they point at.

| Key | Env var | Default | What it does |
|---|---|---|---|
| `application.opa.enabled` | `OPA_ENABLED` | `false` | Master switch. While false the PEP is not registered and every decision returns ALLOW — see [The master switch](#the-master-switch). |
| `application.opa.url` | `OPA_URL` | `http://localhost:8181` | PDP base URL. Must be `https://` in any non-local environment: this service enforces mTLS for all service-to-service traffic, and the client keystore/truststore under `application.client` are applied automatically to any `https://` call. |
| `application.opa.decision-path` | `OPA_DECISION_PATH` | `/v1/data/management_node/decision` | Path appended to the base URL. Resolves to the `decision` rule of the `management_node` package; rename the package or the rule and this must follow. |
| `application.opa.connect-timeout` | `OPA_CONNECT_TIMEOUT` | `2s` | Maximum time to establish a connection to the PDP. A timeout is a DENY, since the PDP fails closed. |
| `application.opa.read-timeout` | `OPA_READ_TIMEOUT` | `3s` | Maximum time to wait for a decision. Discovery asks for one decision per candidate product, so this bounds each decision, not the request. |
| `application.opa.protected-paths` | — | `/api/v1/configuration/**` | Spring MVC path patterns the whole-request PEP intercepts. Required and non-empty: Spring's `MappedInterceptor` reads an empty include list as "match every path", not "match nothing". Product discovery is deliberately absent — see [The two enforcement points](#the-two-enforcement-points). |
| `application.opa.forwarded-headers` | — | `content-type`, `accept`, `x-correlation-id`, `x-forwarded-for`, `user-agent` | Request headers allowed into `input.request.headers` — see [Headers are allow-listed](#headers-are-allow-listed). `Authorization`, `Cookie`, `Set-Cookie` and `Proxy-Authorization` are refused in code whatever is listed here. |
| `application.opa.log-input` | `OPA_LOG_INPUT` | `false` | Logs each decision document before it is sent. Carries the token's claims verbatim — see [Seeing what is sent to the PDP](#seeing-what-is-sent-to-the-pdp). |
| `application.opa.log-output` | `OPA_LOG_OUTPUT` | `false` | Logs each decision the PDP returned — see [Seeing what the PDP returned](#seeing-what-the-pdp-returned). |

The `dev` profile (`application-dev.yml`) turns `enabled`, `log-input` and `log-output` on and
protects `/api/v1/product/**` instead.

## The master switch

Policy enforcement is **off by default**. `application.opa.enabled` gates the whole mechanism:

```yaml
application:
  opa:
    enabled: ${OPA_ENABLED:false}
```

| | `enabled: false` (default) | `enabled: true` |
|---|---|---|
| Policy Enforcement Point | not registered | registered on `protected-paths` |
| Every decision | returns ALLOW without contacting OPA | evaluated by the PDP |
| Product discovery | returns **every** candidate, unfiltered | returns only authorised products |
| OPA reachable? | irrelevant, never called | required — it fails closed |

At startup with the switch off, the service logs:

```
WARN  OPA is switched off in configuration (application.opa.enabled=false). Policy will NOT be
      evaluated: every decision returns ALLOW and the Policy Enforcement Point is not registered.
      Set application.opa.enabled=true to enforce policy.
```

The warning is emitted **once at startup**, not per decision — product discovery alone asks for one decision per candidate product, so a per-call warning would bury the message it exists to make visible. Individual skipped evaluations are logged at `DEBUG`.

> **Note:** the switch is a development and bring-up convenience. With it off there is no per-product filtering at all, so discovery returns products a policy would have denied. Any environment relying on policy to restrict access must set `application.opa.enabled=true`.

Certificate validation is unaffected by this switch — only policy enforcement is disabled.

## The two enforcement points

Authorisation happens in two different places, and they are not interchangeable.

| | Whole-request PEP | Per-candidate evaluation |
|---|---|---|
| Component | `PolicyEnforcementInterceptor` | `ProductDiscoveryServiceImpl` |
| Applies to | paths in `application.opa.protected-paths` | product discovery only |
| Decisions per request | one | one **per candidate product** |
| On DENY | request rejected with `403` | that product is omitted from the response |

The whole-request PEP is a gate: one decision, and a denial ends the request. Per-candidate evaluation is a filter: the request succeeds, and the response contains only the products the PDP allowed. A caller cannot tell a denied product from a non-existent one — which is the point.

> **Status:** `ProductController` is currently **not wired to `ProductDiscoveryService`**, while how `filters` should narrow the candidate query is settled. The endpoint binds and validates the criteria and builds the policy input, but returns an empty product list without asking for any decision — so no policy is evaluated on the discovery path today, and the per-candidate behaviour described here is what `ProductDiscoveryServiceImpl` does once the controller calls it again.

Product discovery is deliberately **not** listed in `protected-paths`. If it were, the interceptor would make a single whole-endpoint decision and the per-product filtering would never run.

## The decision request

Every decision is a `POST` to `application.opa.decision-path` with a single JSON document:

```json
{
  "input": {
    "subject": {
      "kind": "service",
      "user_id": "ia-data-product-catalogue-ui",
      "clientId": "ia-data-product-catalogue-ui",
      "token": { "azp": "ia-data-product-catalogue-ui", "exp": 1789392949 },
      "organisation": {
        "key": "FEDERATOR_ENV",
        "attributes": { "nationality": "GB" }
      }
    },
    "action": "discover",
    "resource": {
      "kind": "product",
      "id": "42",
      "attributes": { "classification": "OFFICIAL", "regions": ["UK", "EU"] }
    },
    "request": {
      "headers": { "content-type": "application/json" },
      "query": {},
      "path": "/api/v1/product/discover",
      "method": "POST",
      "body": { "text": "planning" }
    }
  }
}
```

## The decision document

Every policy answers with the same document, whatever it is deciding about, so a caller never has
to know which rule replied:

```json
{
  "result": {
    "allow": true,
    "allowed_filtered_attributes": ["name", "topic"],
    "denied_filtered_attributes": ["internal_owner"],
    "masked_filtered_attributes": ["contact_email"]
  }
}
```

| Field | Meaning |
|---|---|
| `allow` | whether the action is permitted |
| `allowed_filtered_attributes` | attributes the subject may see in full |
| `denied_filtered_attributes` | attributes that must be withheld entirely |
| `masked_filtered_attributes` | attributes that may be returned only in masked form |

It is read into `DefaultPolicyDecisionOutput`. A policy that decides only allow/deny still returns
the three lists, empty; the lists are never null in Java, so no caller null-checks them. The rule
that assembles the document is `decision`, which is what `application.opa.decision-path` points at
(`/v1/data/management_node/decision`).

**Anything that cannot be read as that document is DENY** — a missing `result`, a result that is
not an object, a malformed body, a non-200, a timeout, or OPA being unreachable.
`PolicyDecisionClient` catches every exception and returns DENY, so the PDP fails closed. One
older shape is still accepted: a bare `{"result": true}` is read as the verdict with empty lists,
so a policy that has not yet been moved to the `decision` rule keeps working.

### Where the decision goes

A decision does not stop at the enforcement point that asked for it.

- The **whole-request PEP** publishes the allowed decision as a request attribute
  (`DefaultPolicyDecisionOutput.REQUEST_ATTRIBUTE`). A denied request publishes nothing — it never
  reaches a handler.
- `PolicyDecisionOutputArgumentResolver` injects it into any controller method that declares a
  `DefaultPolicyDecisionOutput` parameter, so a handler passes the decision down to the service
  layer instead of asking for a second one that could answer differently. Where no whole-request
  decision was taken — the path is not in `protected-paths`, or policy enforcement is off — the
  parameter resolves to `DefaultPolicyDecisionOutput.ALLOW`: allow with nothing filtered. That
  grants nothing, since a request only reaches a handler if the PEP allowed it or never gated it.
- **Per-candidate evaluation** narrows that request decision by each candidate's own, via
  `combinedWith`: the action is permitted only if both permit it, and the attribute lists are
  merged with withholding winning over disclosure — anything denied or masked by either decision
  is not left in the allowed list.

## Where each field comes from

`PolicyInputFactory` is the only class that knows how the input is sourced. Callers ask for a decision and never restate identity plumbing.

| Field | Source |
|---|---|
| `subject.kind` | `service` when the token's `preferred_username` starts with `service-account-`, otherwise `user` |
| `subject.user_id` | the client id for a service account; the `email` claim (falling back to `preferred_username`, then `sub`) for a user |
| `subject.clientId` | the OAuth client the token was issued to, from the principal |
| `subject.token` | the JWT's claims verbatim. Timestamp claims (`exp`, `iat`) are emitted as epoch seconds, not serialised `Instant`s, so a policy can compare them numerically |
| `subject.organisation.key` | the principal's `organisation` claim (e.g. `FEDERATOR_ENV`) |
| `subject.organisation.attributes` | live `ORGANISATION`-scoped rows from `policy_attribute_value`, for the organisation that key identifies |
| `action` | the **last** path segment |
| `resource.kind` | the path segment **before** the action |
| `resource.id` | the entity id, when the decision concerns one entity. Null for whole-endpoint decisions |
| `resource.attributes` | live `PRODUCT`-scoped rows for that entity |
| `request.headers` | allow-listed request headers (see below) |
| `request.query` | parsed from the **query string**, every value of a repeated parameter kept, percent-decoded |
| `request.path` / `request.method` | the request URI and HTTP method |
| `request.body` | the parsed request body as structured attributes (see below) |

### Path-derived action and resource kind

`action` and `resource.kind` are read off the request path — the last segment is the action, the one before it the resource kind:

```
/api/v1/product/discover   ->  action "discover" on kind "product"
/api/v1/configuration/producer  ->  action "producer" on kind "configuration"
```

### Client and organisation both come from the principal

`subject.clientId` and `subject.organisation.key` are sourced the same way — from the authenticated principal — but they are distinct facts. The client id names the OAuth client the token was issued to; the organisation names who that client acts for. Several clients can belong to one organisation, so a policy may gate on either.

The organisation is resolved in two steps:

1. `subject.organisation.key` is taken from the principal's `organisation` claim.
2. That key is looked up (`OrganisationService.findIdByKey`) to find the organisation row, and that row's `ORGANISATION`-scoped attributes become `subject.organisation.attributes`.

Two cases are handled deliberately rather than by failing:

- **Key matches no organisation row** — the key still reaches the PDP, with empty attributes. The policy decides what an unknown organisation means; the decision does not error.
- **Token carries no organisation claim** — `EnhancedPrincipal` substitutes the sentinel `UNKNOWN_ORG` (`UnknownIdentifiers.UNKNOWN_ORG`). That sentinel is *not* forwarded: `organisation` is emitted with a null key and empty attributes, so a policy cannot come to depend on a magic string that only means "the claim was missing".

> **Note:** the organisation claim is matched against `organisation.organisation_key`. If the claim values your IdP issues (e.g. `FEDERATOR_ENV`) differ from the keys held in the database (e.g. `ENV`), no row will match and attributes will always be empty. Keep the two aligned, or map between them before the lookup.

### Attribute typing

Attribute values keep their JSON type — a numeric attribute arrives as a number, a boolean as a boolean:

```json
"attributes": { "tier": 3, "sensitive": true, "regions": ["UK", "EU"], "nationality": "GB" }
```

A `multi_valued` attribute is held as **one `policy_attribute_value` row per value**, and all of its live values are collected into an array.

The shape follows the **definition, not the data**: a multi-valued attribute is always an array, even when only one value is recorded. That means a policy can index it without first checking how many values happen to exist —

```rego
input.resource.attributes.regions[_] == "UK"
```

— works whether the entity has one region or five. Conversely a single-valued attribute is always a scalar, never a one-element array.

Two anomalies are handled rather than hidden, both logged at `WARN`:

- a **single-valued** attribute holding several live values — the first is used, since the declared shape is scalar
- the **same name defined in two namespaces** for one entity — the first definition wins

A single row whose stored value is itself a JSON array is also accepted for a multi-valued attribute, and is flattened rather than nested, so both storage conventions yield the same array.

Attributes are keyed by name alone, not `namespace.name`, so a policy reads `input.resource.attributes.nationality`. Names are unique per entity in practice; if two namespaces define the same name for one entity, the first is kept and the clash is logged.

### Query parameters and the request body

Both reach the policy as **data**, not text, so a rule can address them directly:

```
POST /api/v1/product/discover?page=1&last_page=222
{"text": "planning", "filters": {"key 1": "value", "key 2": [1234, 33, 222]}}
```

becomes

```json
"request": {
  "query": { "page": ["1"], "last_page": ["222"] },
  "path": "/api/v1/product/discover",
  "method": "POST",
  "body": { "text": "planning", "filters": { "key 1": "value", "key 2": [1234, 33, 222] } }
}
```

so `input.request.body.text == "planning"`, `input.request.body.filters["key 2"][_] == 1234` and `input.request.query.page[0] == "1"` all work. Criteria the caller did not supply are omitted rather than sent as nulls.

`query` is parsed from the raw query string rather than the servlet parameters. For a form-encoded POST the container merges posted fields into `getParameterMap`, which would put body content into `query`; reading the query string keeps each field meaning exactly what its name says.

#### Where the body comes from

A servlet body is a one-shot stream: whoever reads it first consumes it. So the body is **supplied by the caller of the factory**, never read from the request inside it — and the two enforcement points get it from different places:

- **Product discovery** — `ProductController` already has the body bound to `ProductDiscoveryRequestDTO`, and passes it straight through. Policy therefore sees the *accepted* criteria, which is what the endpoint will actually act on; **fields the DTO does not declare are dropped at binding and never reach the PDP**. The DTO declares `text` and `filters`; `filters` is an untyped map, so arbitrary keys do survive binding and reach the policy, but a misspelt top-level field (`fitlers`) does not.
- **The whole-request PEP** — `preHandle` runs before the handler binds anything, so there is nothing bound to pass. `PolicyBodyCachingFilter` buffers the body first and `PolicyRequestBodyReader` parses it out of that buffer, so the interceptor can hand the body to the factory *and* the handler still gets a stream to bind.

Buffering is deliberately narrow, because holding a body in memory costs what streaming does not. A request is buffered only when all of these hold:

| Condition | Otherwise |
|---|---|
| `application.opa.enabled` is true | no decision is made, so no body is needed |
| the path matches `application.opa.protected-paths` | product discovery needs no buffer — its controller has the body bound already |
| a declared `Content-Length` of 1..65536 bytes | a larger body is streamed through and logged at `WARN`; an undeclared (chunked) length is passed through, since reading part of it to measure it would corrupt the stream this exists to preserve |

`PolicyRequestBodyReader` then parses the buffer as JSON (`application/json` or any `+json`), so a rule reaches `input.request.body.filters["key 1"]` rather than a string. It yields **no body** rather than an error for an unbuffered request, an empty body, a non-JSON content type, or JSON that will not parse — a malformed body is the handler's `400` to give, and failing the decision first would report it as a misleading `403`.

> **Note:** the body is sent to the PDP as-is. Do not accept secrets in a request body on a policy-aware path: unlike headers there is no allow list, so whatever is posted is what the policy sees.

### Headers are allow-listed

Only headers named in `application.opa.forwarded-headers` are sent:

```yaml
application:
  opa:
    forwarded-headers:
      - content-type
      - accept
      - x-correlation-id
      - x-forwarded-for
      - user-agent
```

`Authorization`, `Cookie`, `Set-Cookie` and `Proxy-Authorization` are **refused in code regardless of configuration**. The token's claims already reach the PDP as `subject.token`, so forwarding the credential itself would add exposure without adding information — particularly since `OPA_URL` defaults to plain `http://localhost:8181` in local development.

## Seeing what is sent to the PDP

`application.opa.log-input` turns on a log of every decision document, written by
`PolicyInputLogger` immediately before `PolicyDecisionClient` calls the PDP:

```yaml
application:
  opa:
    log-input: ${OPA_LOG_INPUT:false}
```

It is **off by default and must stay off wherever logs are retained or shipped** — `subject.token`
is the caller's claims verbatim. Switching it on emits a `WARN` at startup saying so. Each decision
then logs a summary of the fields a policy is most likely to key on, followed by the payload exactly
as it goes on the wire, and the resulting decision:

```
PDP decision request -> http://localhost:8181/v1/data/management_node/decision
  subject.kind         : service
  subject.user_id      : ia-data-product-catalogue-ui
  subject.clientId     : ia-data-product-catalogue-ui
  subject.organisation : FEDERATOR_ENV attributes={nationality=GB}
  action               : discover
  resource             : product id=42 attributes={classification=OFFICIAL}
  request              : POST /api/v1/product/discover
  request.headers      : {content-type=application/json}
  request.query        : {page=[1]}
  request.body         : {"text":"planning","filters":{"classification":"OFFICIAL"}}
  payload:
  {
    "input" : { ... }
  }
```

Product discovery asks for one decision per candidate product, so the switch produces one such
block per candidate — another reason it is a bring-up aid rather than something to leave on.

### Reading `request.body`

The summary prints `request.body : <none>` when the caller passed no body at all, and the
serialised document when it passed one. The distinction matters, because an empty body in the
payload has two quite different causes:

- **`<none>`** — no body reached the PDP. On a protected path that means the request was not
  buffered or not parsed: no `Content-Length`, a body over the 64KB limit, a content type that is
  not JSON, or JSON that would not parse. The first three are logged where the decision is made,
  at `DEBUG` or `WARN`; check those lines first.
- **`{"filters":{}}`** — a body was supplied but carried nothing the DTO declares. Its two fields
  are `text` and `filters`; unknown top-level fields are dropped at binding and `text` is omitted
  when unset, so a misspelt field name reduces to this. Check the top-level names against the DTO —
  keys *inside* `filters` are unconstrained and always survive.

### Seeing what the PDP returned

`application.opa.log-output` is the symmetric switch for the other side of the exchange: it logs
the decision the PDP sent back, written by `PolicyOutputLogger` as soon as `PolicyDecisionClient`
reads it:

```yaml
application:
  opa:
    log-output: ${OPA_LOG_OUTPUT:false}
```

Unlike `log-input`, the decision document carries none of the caller's token claims — only the
verdict and the three attribute lists — so switching it on does not expose anything `log-input`
doesn't already. Switching it on still emits a `WARN` at startup, and product discovery still asks
for one decision per candidate product, so it is just as noisy a bring-up aid:

```
PDP decision response <- ALLOW
  action               : discover
  resource             : product id=42
  allowed_attributes   : [name, topic]
  denied_attributes    : [internal_owner]
  masked_attributes    : []
  payload:
  {
    "result" : { ... }
  }
```

## Extending the input

The input is an object graph rather than a flat set of strings so that each part can grow independently. To add a fact:

- a new **subject** fact — add a component to `PolicySubject` and populate it in `PolicyInputFactory`
- a new **resource** fact — usually a new policy attribute definition, which needs no Java change at all: define it, scope it, and it appears under `resource.attributes`
- a new **request** fact — add a component to `PolicyHttpRequest`

Because unset fields are omitted rather than serialised as `null`, adding a component does not change the document a policy already sees.

## Writing a policy

The bundled `docker/opa/policy.rego` defaults to allow — it is a placeholder so local development is not blocked, **not** a policy. A rule matching the input above:

```rego
package management_node

default allow := false

default allowed_filtered_attributes := []

default denied_filtered_attributes := []

default masked_filtered_attributes := []

allow if {
    input.action == "discover"
    input.resource.kind == "product"
    input.subject.organisation.attributes.nationality == "GB"
    input.resource.attributes.classification != "SECRET"
}

masked_filtered_attributes := ["contact_email"] if {
    input.subject.organisation.attributes.nationality != "GB"
}

decision := {
    "allow": allow,
    "allowed_filtered_attributes": allowed_filtered_attributes,
    "denied_filtered_attributes": denied_filtered_attributes,
    "masked_filtered_attributes": masked_filtered_attributes,
}
```

`decision` is the only rule the service queries; the four rules it is assembled from are what a
real policy overrides, one at a time.

## Testing

- `PolicyInputFactoryTest` covers how each field is sourced, including that a configured `Authorization` header is still never forwarded.
- `PolicyBodyCachingFilterTest` covers which requests are buffered, and that a buffered body is still readable — twice — downstream.
- `PolicyRequestBodyReaderTest` covers parsing and each case that yields no body rather than an error.
- `PolicyInputLoggerTest` covers the `log-input` switch, including that an unsupplied body reads as `<none>` rather than an empty document.
- `PolicyOutputLoggerTest` covers the `log-output` switch, including the attribute lists and that a logging failure never throws.
- `PolicyDecisionSerializationTest` pins the wire format, including that attribute JSON types survive and that unset fields are omitted.
- `DefaultPolicyDecisionOutputTest` covers how each shape of PDP result is read into the decision document, and how two decisions combine.
- `PolicyDecisionClientTest` covers the request body and the fail-closed behaviour.
- `PolicyDecisionOutputArgumentResolverTest` covers injecting the published decision into a controller, and what an unprotected path gets instead.
- `ProductDiscoveryServiceImplTest` covers per-candidate evaluation and that denied products leak nothing into the response.

Exercising the PDP end to end needs OPA running — see the OPA stack under `docker/`. Because the PDP fails closed, a stopped OPA makes every protected endpoint return `403`.
