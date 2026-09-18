# Policy Enforcement

**Repository:** `management-node`  
**Description:** `Provides APIs to be accessed by Consumer and Producer Federators for the purpose of dynamic configuration management `  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0 `

---

This document describes how the Management Node asks the Policy Decision Point (OPA) for authorisation decisions: how an endpoint opts in, how the PDP chooses the rule that answers, the shape of the `input` document sent and of the decision returned, and how to add policy to an endpoint.

Policy is enforced **on top of** authentication and role checks, never instead of them. A request must first carry a valid token with the endpoint's role (see [Authentication Requirements](AUTHENTICATION_REQUIREMENTS.md)); policy then decides whether this caller may perform this action, and on what terms.

## Contents

- [Enforcement order](#enforcement-order)
- [Configuration reference](#configuration-reference)
- [The master switch](#the-master-switch)
- [Opting in: `@Policy`](#opting-in-policy)
- [Dispatch](#dispatch)
- [Actions with no dedicated rule](#actions-with-no-dedicated-rule)
- [The decision document](#the-decision-document)
- [The decision request](#the-decision-request)
- [Where each field comes from](#where-each-field-comes-from)
- [Seeing what is sent to the PDP](#seeing-what-is-sent-to-the-pdp)
- [Adding policy to a new endpoint](#adding-policy-to-a-new-endpoint)
- [Writing a policy](#writing-a-policy)
- [Reference endpoints](#reference-endpoints)
- [Testing](#testing)

## Enforcement order

Every request to a protected endpoint is judged in this order, and a request refused at any step never reaches the next:

| Step | Mechanism | Refusal |
|---|---|---|
| 1. authentication | security filter chain (`BearerTokenAuthenticationFilter`) | `401` |
| 2. authorization | method security: `@PreAuthorize` (and any `@Secured`/JSR-250) | `403 "Access denied: insufficient permissions for this operation"` |
| 3. organisation certificate | `CertificateValidationInterceptor`, on `/api/v1/configuration/**` | `403` with the certificate reason, e.g. `"No organisation certificate found"` |
| 4. policy | `PolicyEnforcementInterceptor`, on methods carrying `@Policy` | `403 "Access denied by policy"`, with the policy's caller-facing `reasons` |
| 5. the handler | the controller method | — |

So a caller without the endpoint's role is never checked for a certificate and **never sent to the PDP**.

Steps 3 and 4 are method interceptors, not Spring MVC `HandlerInterceptor`s. A handler interceptor runs before the controller method is invoked, and `@PreAuthorize` is evaluated as part of that invocation — so a handler interceptor always runs *before* authorization, and would consult the PDP for callers authorization then refuses. `RequestEnforcementConfig` instead places both in the same advice chain as method security, ordered after the last pre-invocation authorization step (`CERTIFICATE_VALIDATION_ORDER`, then `POLICY_ENFORCEMENT_ORDER`). A refusal is an `AccessRejectedException`, rendered by `GlobalExceptionHandler` as the `403` above with the logged error id.

Two things still happen before authorization, neither of which calls the PDP: the request body is buffered for `@Policy` handlers (see [Request body buffering](#request-body-buffering)), and handler arguments are resolved — which is why the decision is written into the `Optional<PolicyDecision<...>>` argument by the PEP itself (see [Receiving the decision in the handler](#receiving-the-decision-in-the-handler)).

## Configuration reference

Every key the Policy Enforcement Point reads, with the default from `application.yml`. The yaml
carries one-line comments only; this table is the reference they point at.

| Key | Env var | Default | What it does |
|---|---|---|---|
| `application.opa.enabled` | `OPA_ENABLED` | `false` | Master switch. While false the PEP is not registered and every decision returns ALLOW — see [The master switch](#the-master-switch). |
| `application.opa.url` | `OPA_URL` | `http://localhost:8181` | PDP base URL. Must be `https://` in any non-local environment: this service enforces mTLS for all service-to-service traffic, and the client keystore/truststore under `application.client` are applied automatically to any `https://` call. |
| `application.opa.decision-path` | `OPA_DECISION_PATH` | `/v1/data/dispatch/decision` | Path appended to the base URL. Resolves to the `decision` rule of the `dispatch` package, the single entrypoint that selects a rule per `(resource, action)` — see [Dispatch](#dispatch). |
| `application.opa.connect-timeout` | `OPA_CONNECT_TIMEOUT` | `2s` | Maximum time to establish a connection to the PDP. A timeout is a DENY, since the PDP fails closed. |
| `application.opa.read-timeout` | `OPA_READ_TIMEOUT` | `3s` | Maximum time to wait for a decision. Per-candidate evaluation asks for one decision per candidate, so this bounds each decision, not the request. |
| `application.opa.forwarded-headers` | — | `content-type`, `accept`, `x-correlation-id`, `x-forwarded-for`, `user-agent` | Request headers allowed into `input.request.headers` — see [Headers are allow-listed](#headers-are-allow-listed). `Authorization`, `Cookie`, `Set-Cookie` and `Proxy-Authorization` are refused in code whatever is listed here. |
| `application.opa.log-input` | `OPA_LOG_INPUT` | `false` | Logs each decision document before it is sent. Carries the token's claims verbatim — see [Seeing what is sent to the PDP](#seeing-what-is-sent-to-the-pdp). |
| `application.opa.log-output` | `OPA_LOG_OUTPUT` | `false` | Logs each decision the PDP returned — see [Seeing what the PDP returned](#seeing-what-the-pdp-returned). |

There is no path-pattern setting. Which endpoints are enforced is declared in code with `@Policy`,
not configured — see [Opting in](#opting-in-policy).

The `dev` profile (`application-dev.yml`) turns `enabled`, `log-input` and `log-output` on.

## The master switch

Policy enforcement is **off by default**. `application.opa.enabled` gates the whole mechanism:

```yaml
application:
  opa:
    enabled: ${OPA_ENABLED:false}
```

| | `enabled: false` (default) | `enabled: true` |
|---|---|---|
| Policy Enforcement Point | present, but lets every call through without a decision | acts on methods annotated `@Policy`, after authorization |
| Request body buffering | never | for requests whose handler carries `@Policy` |
| Every decision | returns ALLOW without contacting OPA | evaluated by the PDP |
| Injected `Optional<PolicyDecision<...>>` | always empty | present on an annotated handler that was allowed |
| Per-candidate evaluation | every candidate allowed, unfiltered | only candidates the PDP allows |
| OPA reachable? | irrelevant, never called | required — it fails closed |

At startup with the switch off, the service logs:

```
WARN  OPA is switched off in configuration (application.opa.enabled=false). Policy will NOT be
      evaluated: every decision returns ALLOW and the Policy Enforcement Point is not registered.
      Set application.opa.enabled=true to enforce policy.
```

The warning is emitted **once at startup**, not per decision — per-candidate evaluation asks for one decision per candidate, so a per-call warning would bury the message it exists to make visible. Individual skipped evaluations are logged at `DEBUG`.

> **Note:** the switch is a development and bring-up convenience. With it off there is no policy at all: annotated endpoints run for any caller holding the role, and per-candidate filtering returns everything. Any environment relying on policy to restrict access must set `application.opa.enabled=true`.

Certificate validation is unaffected by this switch — only policy enforcement is disabled.

## Opting in: `@Policy`

A controller method is enforced only when it is annotated:

```java
@PostMapping("/subscribe")
@PreAuthorize("hasAuthority('ROLE_management-node:product_subscribe')")
@Policy(resource = "product", action = "subscribe", details = ProductSubscriptionPolicyDecisionDetails.class)
public ProductSubscriptionResponseDTO subscribe(
        @Valid @RequestBody ProductSubscriptionRequestDTO request,
        @Parameter(hidden = true) Optional<PolicyDecision<ProductSubscriptionPolicyDecisionDetails>> policyDecision) { ... }
```

| Attribute | Becomes | Purpose |
|---|---|---|
| `resource` | `input.resource.kind` | selects the rule, together with `action` |
| `action` | `input.action` | selects the rule, together with `resource` |
| `details` | — | the `PolicyDecisionDetails` subclass the rule's `details` object is read into; defaults to `PolicyDecisionDetails` itself (the generic form) |

The resource and action are taken from the annotation, **never from the URL**. Renaming a path, adding a path variable or versioning the API does not change which rule answers.

### What an un-annotated endpoint gets

Nothing. The Policy Enforcement Point only advises methods carrying `@Policy`, so a handler without it is never judged: no input is built, no body is buffered, and the PDP is never called. Such an endpoint is still protected by authentication and its `@PreAuthorize` role check.

This makes enforcement opt-in per endpoint. The trade-off is deliberate: a URL pattern in configuration can silently stop matching after a path change, whereas an annotation moves with the method it protects.

### When the PDP denies

The request is rejected with `403` before the handler runs. The body carries the message `"Access denied by policy"`, the reasons the caller may act on, and an error id:

```json
{"status": 403, "message": "Access denied by policy", "reasons": ["schedule.type_not_permitted"], "errorId": "…"}
```

The decision is logged at `WARN` with the client id, path, action, method, **every** reason, provenance and the same id as `correlationId`, so the response can be traced to the log line. A denial can only happen to a caller authorization has already accepted.

Not every reason reaches the caller. `PolicyDecision.callerReasons()` leaves out reasons starting `dispatch.` or `policy.`, such as `dispatch.resource_fallback`, `dispatch.contract_mismatch` or `policy.details_unreadable`. Those describe how policy is wired rather than anything the caller can change, so they stay in the log. A refusal with no caller-facing reasons (for example, the PDP was unreachable) omits `reasons`. `reasons` appears only on policy refusals; every other error response has no such field.

### Receiving the decision in the handler

Every `@Policy` handler receives the decision by declaring an `Optional<PolicyDecision<D>>` parameter, where `D` is the type named in `@Policy(details = ...)`, so it reads its rule's details typed and can pass the decision to the service layer instead of asking for a second one that could answer differently. Mark the parameter `@Parameter(hidden = true)` so it stays out of the OpenAPI document. Because the PEP runs inside the handler invocation — after arguments are resolved — `PolicyDecisionArgumentResolver` supplies the parameter and the PEP writes the allowed decision into it before the method body runs. The decision is also published as a request attribute (`PolicyDecision.REQUEST_ATTRIBUTE`).

| Endpoint | Parameter |
|---|---|
| `POST /api/v1/product/discover` | `Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>>` |
| `POST /api/v1/product/subscribe` | `Optional<PolicyDecision<ProductSubscriptionPolicyDecisionDetails>>` |
| `GET /api/v1/product/{productId}` | `Optional<PolicyDecision<ProductViewPolicyDecisionDetails>>` |
| `GET /api/v1/configuration/producer`, `/consumer` | `Optional<PolicyDecision<PolicyDecisionDetails>>` (generic; no typed details yet) |

| Situation | Parameter value |
|---|---|
| `enabled: true`, handler annotated, PDP allowed | present, with `details` already read into the `@Policy(details = ...)` type |
| `enabled: false` | empty — no decision was taken, and an empty value says so rather than standing in for a verdict nobody reached |

A denied request never reaches the handler. Only the `Optional` form is supported, so every caller has to handle the switched-off case.

### Startup validation

Annotations are checked when the application starts; a violation fails startup rather than surfacing as a runtime `403`.

| Rule | Why |
|---|---|
| `resource` and `action` match `[a-z][a-z0-9_]*` | each names a Rego package segment (`policies.<resource>.<action>`) |
| neither is `fallback` | `fallback` is reserved for the resource and global fallback rules |
| a handler declaring `Optional<PolicyDecision<...>>` carries `@Policy` | without it no decision is ever taken, so the parameter could only ever be empty — almost certainly a missing annotation |
| that parameter's details type is assignable from the `@Policy` details type | otherwise the decision taken could never be handed to it |
| `details` is concrete and has a no-argument constructor | details are bound by Jackson, and a decision no rule answered carries empty details of that type |

### Request body buffering

A servlet body is a one-shot stream, and the handler binds it before the PEP runs. `PolicyBodyCachingFilter` therefore buffers the body before either, and `PolicyRequestBodyReader` parses it out of that buffer, so the PEP sends the body **as the caller sent it** — including fields the handler's DTO would drop at binding — and the handler still gets a stream to bind. Buffering happens before authorization; it only holds bytes in memory and never contacts the PDP.

Buffering holds a body in memory, so it is narrow. A request is buffered only when all of these hold:

| Condition | Otherwise |
|---|---|
| `application.opa.enabled` is true | no decision is made, so no body is needed |
| the handler the request resolves to carries `@Policy` | an un-annotated endpoint is never sent to the PDP |
| a declared `Content-Length` of 1..65536 bytes | a larger body is streamed through and logged at `WARN`; an undeclared (chunked) length is passed through, since reading part of it to measure it would corrupt the stream this exists to preserve |

`PolicyRequestBodyReader` parses the buffer as JSON (`application/json` or any `+json`), so a rule reaches `input.request.body.productId` rather than a string. It yields **no body** rather than an error for an unbuffered request, an empty body, a non-JSON content type, or JSON that will not parse — a malformed body is the handler's `400` to give, and failing the decision first would report it as a misleading `403`.

### Per-candidate evaluation

The PEP makes one decision per request: a gate. Some endpoints also authorise a *set* of resources, where the response should contain only what the caller may see. Product discovery uses both, from one rule: `@Policy(resource = "product", action = "discover")` gates whether the caller may discover at all and hands the handler that decision, and `ProductDiscoveryServiceImpl` asks the same rule once per candidate product. `policies.product.discover` tells the two apart by `input.resource.id` — absent for the request, set for a candidate.

| | Whole-request (`@Policy`) | Per-candidate |
|---|---|---|
| Component | `PolicyEnforcementInterceptor` | the service, calling `PolicyDecisionClient` |
| Target | from the annotation | a `PolicyTarget` built by the caller |
| Decisions per request | one | one **per candidate** |
| `resource.id` / `resource.attributes` | null / empty | the candidate's id and its `PRODUCT`-scoped attributes |
| On DENY | request rejected with `403` | that candidate is omitted from the response |

The caller builds the input with `PolicyInputFactory.create(request, body, PolicyTarget.of("product", "discover"))` — or `new PolicyTarget<>(resource, action, detailsType)` for typed details, as discovery does with `ProductDiscoveryPolicyDecisionDetails` — then varies only the resource per candidate. A caller cannot tell a denied candidate from a non-existent one, which is the point.

Each candidate decision is narrowed by the request decision, when there is one, via `combinedWith`:

- the action is permitted only if both permit it;
- reasons are the union of both, de-duplicated and sorted;
- provenance is the candidate decision's when it has one (it is the more specific answer), otherwise the request decision's;
- details are combined by `PolicyDecisionDetails.narrowedBy`. By default the candidate's details win when they carry anything (details count as absent when they equal empty details of their type). `ProductDiscoveryPolicyDecisionDetails` overrides it so its attribute lists are merged, withholding winning over disclosure — anything denied or masked by either decision is not left in the allowed list — while its other fields are the candidate's. Both decisions carry the same details type, so they combine without casting at the call site.

> **Status:** `ProductController`'s discovery handler is policy-enforced and receives the request decision as `Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>>`, which it logs. It is currently **not wired to `ProductDiscoveryService`**, while how `filters` should narrow the candidate query is settled, so it returns an empty product list without asking for per-candidate decisions. The per-candidate behaviour above is what `ProductDiscoveryServiceImpl` does once the controller calls it, passing that request decision to be narrowed.

## Dispatch

The service never names the rule that answers. Every decision is posted to one entrypoint, `data.dispatch.decision` (`docker/opa/policies/dispatch.rego`), which selects a rule from `input.resource.kind` and `input.action`. Rules can therefore be added, shared between actions or rolled back without a Java release.

### Resolution order

The first step that applies wins:

| # | `policy.resolution` | Looks for | Reason added |
|---|---|---|---|
| 1 | `route` | an enabled entry in `data.routing.routes` for this resource and action; lowest `priority` wins, `id` breaks ties | — |
| 2 | `exact` | module `policies.<resource>.<action>` | — |
| 3 | `resource_fallback` | module `policies.<resource>.fallback` | `dispatch.resource_fallback` |
| 4 | `global_fallback` | module `policies.fallback` | `dispatch.global_fallback` |
| — | `none` | nothing resolved | `dispatch.no_policy` (deny) |

A *module* here is a loaded package exposing a `decision` rule. Rules are reached by dynamic reference into `data.policies`, so a module becomes resolvable simply by being loaded under that prefix; the dispatcher needs no edit.

### Deny without falling through

Dispatch moves to the next step **only when a module is absent**. Every other problem denies, because a more general rule quietly answering for a broken specific one would hide the breakage:

| Situation | Reason | `policy.resolution` |
|---|---|---|
| nothing resolves, not even the global fallback | `dispatch.no_policy` | `none` |
| a matched route names a module that is not loaded | `dispatch.route_policy_missing` | `route` |
| the selected module's `contract` is missing or is not `management-node.decision/1` | `dispatch.contract_mismatch` | the step that selected it |
| the selected module's `decision` is undefined for this input, or not an object | `dispatch.decision_undefined` | the step that selected it |

A matched route is final: if its module is missing the request is denied, not passed on to exact or fallback resolution.

> **Note:** a module counts as present when it declares any of `contract`, `version` or `decision`. A rule whose `decision` is *undefined* for a given input — for example `decision := {...} if { ... }` with a condition that fails — still declares its contract and version, so it is found and denied with `dispatch.decision_undefined`. It is never handed to a fallback, which could allow what the dedicated rule never decided.

Each of these deny documents is fully shaped: `allow: false`, `details: {}`, the reason above, and provenance.

### What is copied from a rule

The dispatcher does not return a rule's `decision` verbatim. It copies only the contract's fields, each type-checked against its deny default:

- `allow` is true only if the rule's value is literally `true`;
- `reasons` that is not an array becomes `[]`;
- no other top-level field is copied, so a rule that returns attribute lists outside `details` has them dropped;
- `details` that are not an object become `{}`;
- the dispatch reason, if any, is merged into `reasons`, which are then de-duplicated and sorted;
- `policy` is always the dispatcher's own provenance — a rule cannot set or forge it.

A malformed field therefore narrows access rather than leaking through.

### Provenance

Every decision, deny-shaped ones included, carries `policy`:

| Field | Value |
|---|---|
| `id` | the module that answered as `<resource>.<action>` (e.g. `product.fallback`), `fallback` for the global fallback, or `none` |
| `version` | the module's declared `version`, or `none` if it declares none or nothing resolved |
| `resolution` | `route`, `exact`, `resource_fallback`, `global_fallback` or `none` |

Provenance lets "no rule", "broken route" and "the rule said no" be told apart after the fact, and records which rule decided rather than merely that one did. On the Java side it is `PolicyProvenance`; a decision no rule answered (switched off, PDP unreachable, response unreadable) carries `PolicyProvenance.NONE`.

### Worked example

With the shipped rules and routing data (organisation known, `GET` for reads):

| `@Policy` | Resolution | `policy.id` | Outcome | `reasons` |
|---|---|---|---|---|
| `product` / `discover` | `exact` | `product.discover` | allow for a UK organisation with a clearance, with its search contract in `details` (a `POST`, so the read-only fallback would have refused it) | `[]` when allowed |
| `product` / `subscribe` | `exact` | `product.subscribe` | decided by the subscription rule; terms in `details` | `[]` when allowed |
| `product` / `view` | `exact` | `product.view` | allow from `OFFICIAL-SENSITIVE` clearance, `details: {"access_level": "full"\|"summary", ...}` | `[]` when allowed |
| `product` / `browse` | `route` (`route-product-browse`) | `product.fallback` | allow, `details: {"access_level": "read"}` | `[]` |
| `invoice` / `read` | `global_fallback` | `fallback` | deny | `["dispatch.global_fallback", "policy.no_specific_rule"]` |

`browse` has no module of its own, and a route sends it to the product fallback. A route adds no dispatch reason — it is an explicit decision, not a fallback. An action with neither a module nor a route (for example `product` / `export`) resolves to the product fallback as `resource_fallback`, with reason `dispatch.resource_fallback`.

## Actions with no dedicated rule

Annotating an endpoint and writing its rule are separate steps, and an endpoint can be annotated before anyone writes a rule for it. The design answer to "what decides an action nobody wrote a rule for" has three parts.

**1. The resource fallback decides for its resource.** `policies.<resource>.fallback` answers every action on that resource without its own rule. The owner of a resource chooses a safe default once, instead of every new action inheriting whatever happens to be nearest.

| Module | Behaviour |
|---|---|
| `policies.product.fallback` | **read-only for a known organisation**: allow only when `input.subject.organisation.key` is a non-empty string **and** `input.request.method` is `GET` or `HEAD`. Deny reasons `organisation.missing`, `action.not_read_only` (every failing condition is reported). `details` is `{"access_level": "read"}` when allowed, `{}` otherwise. |
| `policies.configuration.fallback` | allow, `details: {}`. A local placeholder for the configuration endpoints (`action` `producer` and `consumer`); who may call them is already gated by roles and the client certificate. It is the first thing to replace with real rules. |

The product fallback judges the **HTTP method**, not the action name. Action names are free-form, so a rule guessing which ones are harmless would be guessing; the method is what the caller is actually doing. Nothing that changes state is allowed until a rule for that action exists. A read that has to be a `POST`, such as discovery with a search body, therefore needs its own rule — which is why `policies.product.discover` exists rather than relying on the fallback.

**2. The global fallback denies.** `policies.fallback` answers any resource with neither a dedicated rule nor a resource fallback, with `allow: false` and reason `policy.no_specific_rule`.

Fail-closed is the global default because the global fallback answers exactly the requests nobody has thought about. An endpoint annotated before its rule is written, a resource added without a fallback, or a typo in `@Policy` all land here. Allowing would grant access that no one decided to grant, and the mistake would be invisible because everything would appear to work; denying makes the gap show up as a `403` with a reason and provenance that name it.

**3. Routes share a rule without Rego changes.** When an action should be decided exactly like an existing rule, add an entry to `docker/opa/policies/routing/data.json` instead of writing a module:

```json
{"id": "route-product-browse", "resource": "product", "action": "browse",
 "policy": "product.fallback", "priority": 10, "enabled": true}
```

`policy` is `<resource>.<action>` of the module to use (`fallback` for the global one). Lowest `priority` wins, `id` breaks ties, entries with `enabled` not `true` are ignored. A route can also point an action with a dedicated rule at a different one, since routes are consulted first — for example to roll back to an earlier rule.

## The decision document

Every decision has the same envelope, whichever rule answered, plus a rule-specific `details` object:

```json
{
  "result": {
    "allow": true,
    "reasons": ["dispatch.resource_fallback"],
    "policy": {"id": "product.fallback", "version": "policies.product.fallback/1.0.0", "resolution": "resource_fallback"},
    "details": {"access_level": "read"}
  }
}
```

It is read into `PolicyDecision<D extends PolicyDecisionDetails>`.

### Generic envelope

Every rule fills these the same way, and Java reads them into typed fields:

| Field | Java | Meaning |
|---|---|---|
| `allow` | `allow()` | whether the action is permitted |
| `reasons` | `reasons()` | sorted, stable codes explaining the decision, e.g. `organisation.missing` |
| `policy` | `policy()` | provenance — see [Provenance](#provenance) |

Nothing is null in Java: absent `reasons` are empty and immutable, absent `policy` is `PolicyProvenance.NONE`, absent `details` are empty details of the declared type. A policy that decides only allow/deny returns no reasons and `details: {}`.

Attribute filtering is **not** part of the envelope. Which attributes a caller may see, must not see, or may see only masked is a term of the rule that needs it, so it travels in that rule's `details` — today only discovery's (see the table below).

### Dynamic `details`

`details` belongs to the rule: each rule defines its own shape, and `{}` is valid. On the Java side every shape is a `PolicyDecisionDetails`, and the decision is generic over it — `PolicyDecision<D extends PolicyDecisionDetails>`:

- **`PolicyDecisionDetails`** is the generic form. It keeps every field the rule returned, in order, readable through `additional()`. Endpoints whose details nothing acts on yet (the configuration endpoints) use it.
- **A subclass per rule** declares the fields an endpoint acts on as typed properties. Anything the subclass does not declare still lands in `additional()`, so a rule can grow its details without breaking the reader.

| Details type | Rule | Fields |
|---|---|---|
| `ProductDiscoveryPolicyDecisionDetails` | `policies.product.discover` | `evaluation()`, `allowedFilteredFields()`, `deniedFilteredFields()`, `maskedFilteredFields()`, `allowedFilteredAttributes()`, `deniedFilteredAttributes()`, `maskedFilteredAttributes()`; grouped as `fields()` and `attributes()` |
| `ProductSubscriptionPolicyDecisionDetails` | `policies.product.subscribe` | `requiresApproval()`, `maxValidityDays()`, `permittedScheduleTypes()` |
| `ProductViewPolicyDecisionDetails` | `policies.product.view` (and the product fallback, for routed actions) | `accessLevel()` |

The endpoint names its type once in `@Policy` and again in its parameter:

```java
@Policy(resource = "product", action = "subscribe", details = ProductSubscriptionPolicyDecisionDetails.class)
public ProductSubscriptionResponseDTO subscribe(
        ..., Optional<PolicyDecision<ProductSubscriptionPolicyDecisionDetails>> policyDecision) {
    policyDecision.map(PolicyDecision::details).map(terms -> terms.requiresApproval()) ...
}
```

`PolicyDecisionClient.evaluate(input, detailsType)` returns `PolicyDecision<D>`, converting the details with the application's `ObjectMapper` so they bind like any other JSON in the service; `evaluate(input)` returns the generic form. No cast is needed anywhere: `decision.details()` is already `D`. `withDetails(E)` returns the same envelope carrying other details.

A details subclass must be concrete, have a no-argument constructor (checked at startup), use `@JsonIgnoreProperties(ignoreUnknown = true)`, and implement `equals` over its fields (e.g. Lombok `@EqualsAndHashCode(callSuper = true)`) so empty details can be recognised when decisions combine. A missing field should read as null, or an empty list for a list.

### Reading failures are DENY

| Situation | Result |
|---|---|
| PDP unreachable, timeout, non-200, malformed body, missing `result` | `DENY`, no reasons, `policy` = `NONE` |
| `result` is a string, number, array or null | `DENY` |
| `details` present but not an object | `DENY`, reason `policy.details_unreadable`, provenance kept |
| `details` cannot be converted to the declared type | `DENY`, reason `policy.details_unreadable`, provenance kept, empty details of the declared type, logged at `WARN` with the rule's id and version |
| `result` is a bare boolean (`{"result": true}`) | read as the verdict, with no reasons, `NONE` provenance and empty details |

`PolicyDecisionClient` catches every exception and returns DENY, so the PDP fails closed. A caller acting on typed details must never be handed a permission whose conditions it cannot read, which is why unreadable details deny rather than allow with empty terms. Provenance is kept so the broken rule can be found. The bare boolean form is accepted so a policy that has not been moved to the envelope keeps working.

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
        "key": "ENV",
        "attributes": { "authorised_classifications": ["OFFICIAL", "SECRET"], "permitted_purposes": ["regulatory_oversight", "service_delivery"], "jurisdictions": ["England", "Wales"] }
      }
    },
    "action": "subscribe",
    "resource": {
      "kind": "product"
    },
    "request": {
      "headers": { "content-type": "application/json" },
      "query": {},
      "path": "/api/v1/product/subscribe",
      "method": "POST",
      "body": { "productId": 42, "scheduleType": "cron" }
    }
  }
}
```

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
| `action` | `@Policy.action`, or the `PolicyTarget` action for per-candidate evaluation |
| `resource.kind` | `@Policy.resource`, or the `PolicyTarget` resource |
| `resource.id` | the entity id, when the decision concerns one entity (per-candidate evaluation). Null for whole-request decisions |
| `resource.attributes` | live `PRODUCT`-scoped rows for that entity |
| `request.headers` | allow-listed request headers (see below) |
| `request.query` | parsed from the **query string**, every value of a repeated parameter kept, percent-decoded |
| `request.path` / `request.method` | the request URI and HTTP method |
| `request.body` | the parsed request body as structured attributes (see below) |

`action` and `resource.kind` never come from the URL, so `request.path` is informational: a rule may inspect it, but rule selection does not depend on it.

### Client and organisation both come from the principal

`subject.clientId` and `subject.organisation.key` are sourced the same way — from the authenticated principal — but they are distinct facts. The client id names the OAuth client the token was issued to; the organisation names who that client acts for. Several clients can belong to one organisation, so a policy may gate on either.

The organisation is resolved in two steps:

1. `subject.organisation.key` is taken from the principal's `organisation` claim.
2. That key is looked up (`OrganisationService.findIdByKey`) to find the organisation row, and that row's `ORGANISATION`-scoped attributes become `subject.organisation.attributes`.

Two cases are handled deliberately rather than by failing:

- **Key matches no organisation row** — the key still reaches the PDP, with empty attributes. The policy decides what an unknown organisation means; the decision does not error.
- **Token carries no organisation claim** — `EnhancedPrincipal` substitutes the sentinel `UNKNOWN_ORG` (`UnknownIdentifiers.UNKNOWN_ORG`). That sentinel is *not* forwarded: `organisation` is emitted with a null key and empty attributes, so a policy cannot come to depend on a magic string that only means "the claim was missing". The shipped rules test for a non-empty string key (`data.lib.decision.organisation_known`), so a null key reads as `organisation.missing`.

> **Note:** the organisation claim is matched against `organisation.organisation_key`. If the claim values your IdP issues (e.g. `FEDERATOR_ENV`) differ from the keys held in the database (e.g. `ENV`), no row will match and attributes will always be empty. Keep the two aligned, or map between them before the lookup.

### Attribute typing

Attribute values keep their JSON type — a numeric attribute arrives as a number, a boolean as a boolean:

```json
"attributes": { "tier": 3, "sensitive": true, "regions": ["UK", "EU"], "nationality": "GB" }
```

This matters to rules: a rule comparing a value must compare it with the right type. The shipped rules read their organisation attributes through `lib/entitlements.rego`, which ignores values that are not strings, so a mistyped attribute gives the lowest entitlement rather than an error.

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

so `input.request.body.text == "planning"`, `input.request.body.filters["key 2"][_] == 1234` and `input.request.query.page[0] == "1"` all work.

`query` is parsed from the raw query string rather than the servlet parameters. For a form-encoded POST the container merges posted fields into `getParameterMap`, which would put body content into `query`; reading the query string keeps each field meaning exactly what its name says.

#### Where the body comes from

The body is **supplied by the caller of the factory**, never read from the request inside it, because reading a one-shot stream there would leave nothing for the handler to bind:

- **The whole-request PEP** passes the body buffered by `PolicyBodyCachingFilter` and parsed by `PolicyRequestBodyReader` — see [Request body buffering](#request-body-buffering). The PDP sees the JSON exactly as posted, including fields the handler's DTO does not declare.
- **Per-candidate evaluation** runs inside the handler, which already has the body bound to its DTO and passes that. Policy then sees the *accepted* criteria, which is what the endpoint will act on; fields the DTO does not declare are dropped at binding and never reach the PDP, and unset fields are omitted rather than sent as nulls.

> **Note:** the body is sent to the PDP as-is. Do not accept secrets in a request body on a policy-enforced endpoint: unlike headers there is no allow list, so whatever is posted is what the policy sees.

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
as it goes on the wire:

```
PDP decision request -> http://localhost:8181/v1/data/dispatch/decision
  subject.kind         : service
  subject.user_id      : ia-data-product-catalogue-ui
  subject.clientId     : ia-data-product-catalogue-ui
  subject.organisation : ENV attributes={authorised_classifications=[OFFICIAL, SECRET], permitted_purposes=[regulatory_oversight, service_delivery], jurisdictions=[England, Wales]}
  action               : subscribe
  resource             : product id=null attributes={}
  request              : POST /api/v1/product/subscribe
  request.headers      : {content-type=application/json}
  request.query        : {}
  request.body         : {"productId":42,"scheduleType":"cron"}
  payload:
  {
    "input" : { ... }
  }
```

Per-candidate evaluation asks for one decision per candidate, so the switch produces one such
block per candidate — another reason it is a bring-up aid rather than something to leave on.

### Reading `request.body`

The summary prints `request.body : <none>` when the caller passed no body at all, and the
serialised document when it passed one. The distinction matters, because an empty body in the
payload has two quite different causes:

- **`<none>`** — no body reached the PDP. On an annotated endpoint that means the request was not
  buffered or not parsed: no `Content-Length`, a body over the 64KB limit, a content type that is
  not JSON, or JSON that would not parse. The first three are logged where the decision is made,
  at `DEBUG` or `WARN`; check those lines first.
- **An empty or near-empty document** (e.g. `{"filters":{}}` on discovery) — a body was supplied
  but carried nothing the handler's DTO declares. This applies where the body passed is the bound
  DTO (per-candidate evaluation): unknown top-level fields are dropped at binding and unset fields
  are omitted, so a misspelt field name reduces to this. Check the top-level names against the DTO.

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
verdict, reasons, provenance and the rule's details — so switching it on does
not expose anything `log-input` doesn't already. Switching it on still emits a `WARN` at startup,
and per-candidate evaluation still asks for one decision per candidate, so it is just as noisy a
bring-up aid. Provenance in this log is the quickest way to see which rule actually answered.

## Adding policy to a new endpoint

1. **Give the endpoint its role check** (`@PreAuthorize`) and document the role in [Authentication Requirements](AUTHENTICATION_REQUIREMENTS.md). Policy applies on top of it.
2. **Annotate the handler** with `@Policy(resource = "...", action = "...")`. Both must match `[a-z][a-z0-9_]*` and must not be `fallback`; startup fails otherwise.
3. **Decide who answers**, in `docker/opa/policies/`:
   - a dedicated rule: `<resource>/<action>.rego`, package `policies.<resource>.<action>`, declaring `contract`, `version` and a total `decision` — see [Writing a policy](#writing-a-policy);
   - or an existing rule, via a route in `routing/data.json`;
   - or deliberately nothing, relying on `policies.<resource>.fallback` — and if the resource has none, the global fallback denies.
4. **Receive the decision**: declare `@Parameter(hidden = true) Optional<PolicyDecision<D>> policyDecision` on the handler. If the rule returns details the endpoint acts on, define `<Resource><Action>PolicyDecisionDetails extends PolicyDecisionDetails` under `model/policy/<resource>/` (see [Dynamic `details`](#dynamic-details)), name it in `@Policy(details = ...)` and use it as `D`; otherwise use `PolicyDecisionDetails`. Handle the empty `Optional` (policy switched off) explicitly.
5. **Write the tests**: a `<action>_test.rego` beside the rule covering the allow case, each deny reason and the `details`; and Java unit tests for the handler, including the empty-decision case and how the details shape the response.
6. **Run the checks** — see [Testing](#testing) — and restart OPA (`docker compose restart opa`) to load the change locally.

## Writing a policy

Each rule is its own module. The dispatcher selects it; the rule only answers.

| Requirement | Detail |
|---|---|
| package | `policies.<resource>.<action>`; `policies.<resource>.fallback` for a resource fallback |
| `contract` | exactly `"management-node.decision/1"`; anything else, or none, is `dispatch.contract_mismatch` |
| `version` | `"policies.<resource>.<action>/<semver>"`; reported in provenance |
| `decision` | an object with `allow`, `reasons` and `details`. **No `policy` key** — the dispatcher adds provenance and ignores any a rule sets |
| totality | `decision` must be defined for every input, with every field present |

Totality matters twice over: a field a rule forgets must still be present and denied, and a `decision` that is undefined for some input is refused with `dispatch.decision_undefined` — so a non-total rule turns into denials rather than into the answer the rule would have given. Start from the shared deny shape in `lib/decision.rego`, which also provides `organisation_known` and `organisation_attributes` so "is the caller's organisation known" means one thing in every rule:

```rego
package policies.invoice.read

import data.lib.decision.deny_shape
import data.lib.decision.organisation_known

contract := "management-node.decision/1"

version := "policies.invoice.read/1.0.0"

decision := object.union(deny_shape, {
	"allow": allow,
	"reasons": reasons,
	"details": details,
})

default allow := false

allow if count(reason_set) == 0

reasons := sort([reason | some reason in reason_set])

reason_set contains "organisation.missing" if not organisation_known

reason_set contains "action.not_read_only" if not input.request.method in {"GET", "HEAD"}

default details := {}

details := {"access_level": "read"} if allow
```

Conventions the shipped rules follow:

- `default` every rule a field is built from, so the document is never undefined.
- Report **every** failing condition as a reason, as a set sorted into a list, so a caller fixes them all at once.
- Reason codes are stable `area.condition` strings (`organisation.missing`, `schedule.type_not_permitted`); callers and audit logs key on them.
- Return `details` even on a denial when they describe terms the caller would be held to.
- Treat missing and `null` input fields the same where the service may send either.

The rule for the endpoint in the example would be annotated `@Policy(resource = "invoice", action = "read")`. See `docker/opa/Readme.md` for the directory layout, running OPA, and querying a decision directly with `curl`.

## Reference endpoints

Three endpoints on `ProductController` exercise the mechanism end to end. Their rules are built from the organisation and product attributes in the database, and are walked through per organisation, with every expected output, in [`docker/opa/policy_sample_stories.md`](../docker/opa/policy_sample_stories.md).

All three rules read the caller through four facts in `lib/entitlements.rego`:

| Fact | From attribute | Meaning |
|---|---|---|
| `clearance` | `authorised_classifications` | highest held: `OFFICIAL`=1, `OFFICIAL-SENSITIVE`=2, `SECRET`=3, `TOP SECRET`=4; 0 if none |
| `has_purpose(p)` | `permitted_purposes` | `p` is held |
| `uk_jurisdiction` | `jurisdictions` | at least one UK nation |
| `local_remit` | `jurisdictions` | at least one area that is not a nation |

### `POST /api/v1/product/discover` — a search contract, at request and candidate level

- Role: `product_discovery`.
- `@Policy(resource = "product", action = "discover", details = ProductDiscoveryPolicyDecisionDetails.class)`; the handler receives the decision and logs its verdict, provenance, reasons, `fields()` and `attributes()`.
- Answered by `policies.product.discover` (resolution `exact`). `POST` is not a read for the product fallback, so without this rule discovery would be refused.

The rule returns the caller's search contract in `details`. Filtering is described separately for **fields** (properties of the product as the API names them, listed in `lib/product.rego`) and **attributes** (policy attributes stored against the product). A name in the request's `filters` counts as a field when it is in that list, and as an attribute otherwise.

| `details` field | Meaning |
|---|---|
| `allowed_filtered_fields` / `allowed_filtered_attributes` | what the caller may filter on |
| `denied_filtered_fields` / `denied_filtered_attributes` | what the caller asked to filter on but may not |
| `masked_filtered_fields` / `masked_filtered_attributes` | what must be masked in results |
| `row_filter` | the condition over product attributes every result must satisfy; `{"type": "literal", "value": false}` when refused |
| `max_page_size`, `obligations` | limits and duties for the search layer |
| `evaluation` | `request`, or `candidate` when `input.resource.id` is set |

| Refused when | Applies at | Reason |
|---|---|---|
| no organisation | both | `organisation.missing` |
| no UK nation in `jurisdictions` | both | `organisation.jurisdiction_not_permitted` |
| no classification held | both | `organisation.clearance_missing` |
| a requested filter field is not allowed | both | `filter.field_not_permitted` |
| a requested filter attribute is not allowed | both | `filter.attribute_not_permitted` |
| the product lacks `identifiability` or `quality_designation` | candidate | `product.attributes_missing` |
| the product fails the `row_filter` | candidate | `product.identifiability_not_permitted`, `product.quality_not_permitted`, `product.population_risk_not_permitted` |

Refusing a disallowed filter, rather than dropping it, keeps a search from quietly returning more than was asked for. The six lists are typed on `ProductDiscoveryPolicyDecisionDetails`. When request and candidate decisions combine, fields and attributes are each merged so that withholding wins. `row_filter`, `max_page_size` and `obligations` are read through `additional()`.

### `POST /api/v1/product/subscribe` — exact rule with typed details

- Role: `product_subscribe`.
- `@Policy(resource = "product", action = "subscribe", details = ProductSubscriptionPolicyDecisionDetails.class)`; the handler receives `Optional<PolicyDecision<ProductSubscriptionPolicyDecisionDetails>>`.
- Body `ProductSubscriptionRequestDTO`: `productId` (required), `scheduleType`, `scheduleExpression`, `destination`. It reaches the PDP as `input.request.body`.
- Answered by `policies.product.subscribe` (resolution `exact`).

| Refused when | Reason |
|---|---|
| no organisation | `organisation.missing` |
| `service_delivery` not held | `organisation.purpose_not_permitted` |
| no `productId` in the body | `request.product_missing` |
| `scheduleType` given and not permitted | `schedule.type_not_permitted` |

`details` are returned whether allowed or denied:

| Field | `ProductSubscriptionPolicyDecisionDetails` | Value |
|---|---|---|
| `requires_approval` | `requiresApproval` | `false` for a regulator (`regulatory_oversight`); otherwise `true` |
| `max_validity_days` | `maxValidityDays` | 365 for research (`statistical_analysis`), otherwise 90 for a regulator, otherwise 30 |
| `permitted_schedule_types` | `permittedScheduleTypes` | `["interval"]` for a local remit; otherwise `["cron", "interval"]` |

The terms live in policy so the service applies what policy decided rather than restating those limits in Java. The handler reads them into `ProductSubscriptionResponseDTO`: `status` is `PENDING_APPROVAL` when approval is required and `ACCEPTED` otherwise, alongside `maxValidityDays` and `permittedScheduleTypes`.

### `GET /api/v1/product/{productId}` — access by clearance

- Role: `product_view`.
- `@Policy(resource = "product", action = "view", details = ProductViewPolicyDecisionDetails.class)`; the handler receives `Optional<PolicyDecision<ProductViewPolicyDecisionDetails>>` and logs its verdict and `accessLevel()` at `DEBUG`.
- Answered by `policies.product.view` (resolution `exact`). The rule sees no product attributes for this endpoint, so it decides on the caller alone.

| Refused when | Reason |
|---|---|
| no organisation | `organisation.missing` |
| clearance below `OFFICIAL-SENSITIVE` | `organisation.clearance_insufficient` |
| method not `GET` or `HEAD` | `action.not_read_only` |

`details`: `access_level` is `full` at `SECRET` or above, `summary` at `OFFICIAL-SENSITIVE`, and `none` when refused. `withheld_fields` lists the product fields that clearance may not see (advisory: the handler does not strip them yet). `required_clearance` is `OFFICIAL-SENSITIVE`.

## Testing

### Rego

Every rule has a `*_test.rego` beside it (ignored by the OPA server):

| File | Covers |
|---|---|
| `dispatch_test.rego` | each resolution kind; each refusal (`dispatch.no_policy`, `dispatch.route_policy_missing`, `dispatch.contract_mismatch`, including a module with no contract); the reserved `fallback` name; route priority; that a rule cannot forge provenance, widen access through malformed fields, or add top-level attribute lists |
| `product/fallback_test.rego` | read-only access for a known organisation, `HEAD`, non-read methods, missing, null and empty organisation keys, and that every failing condition is reported |
| `lib/sample_data_test.rego` | not a test: the sample organisations' and products' attributes exactly as stored, shared by the rule tests |
| `lib/entitlements_test.rego` | each entitlement fact for the three sample organisations, and for missing, single-valued and unknown attributes |
| `product/view_test.rego` | full, summary and refused access per organisation, no organisation, non-read methods, and exact resolution |
| `product/subscribe_test.rego` | each organisation's terms, a refused `cron` for a local remit and its allowed `interval`, optional schedule type, and each deny reason |
| `product/discover_test.rego` | each organisation's full search contract, filter refusals split into fields and attributes, organisation gates, per-candidate results for each sample product, a product without attributes, and exact resolution |

The OPA image has no shell, so run the binary against the host tree from `docker/opa`:

```bash
P="$PWD/policies:/p:ro"
docker run --rm -v "$P" openpolicyagent/opa:1.20.2 check --strict /p
docker run --rm -v "$P" openpolicyagent/opa:1.20.2 test -v /p
docker run --rm -v "$P" openpolicyagent/opa:1.20.2 fmt --diff /p   # no output = formatted
```

### Java

Plain JUnit 5 and Mockito unit tests, run by `./mvnw test`:

- `PolicyDecisionTest` — how each shape of PDP result is read (envelope, reasons, provenance, details, bare boolean, non-object details as `policy.details_unreadable`), `deny`, `of`, `withDetails`, and how two decisions combine, typed details included; that top-level attribute lists are ignored.
- `PolicyDecisionDetailsTest` — the generic form, each endpoint's details subclass bound from its rule's shape (discovery's field and attribute lists included), `narrowedBy` (discovery merging fields and attributes separately), and `empty`.
- `PolicyDecisionClientTest` — the request body, fail-closed behaviour, the switched-off path, and reading details into a declared type, including DENY with provenance kept when they cannot be read.
- `PolicyDecisionSerializationTest` — the wire format, including that attribute JSON types survive and that unset fields are omitted.
- `PolicyInputFactoryTest` — how each field is sourced, including resource and action from the target, and that a configured `Authorization` header is never forwarded.
- `PolicyBodyCachingFilterTest` and `PolicyRequestBodyReaderTest` — which requests are buffered, that a buffered body is still readable downstream, and each case that yields no body rather than an error.
- `PolicyInputLoggerTest` and `PolicyOutputLoggerTest` — the `log-input` and `log-output` switches.
- `PolicyEnforcementInterceptorTest` and `PolicyDecisionArgumentResolverTest` — the enforcement point, publishing the allowed decision, what a handler receives when no decision was taken, and that a decision is only handed to a parameter whose details type can hold it.
- `PolicyAnnotationValidatorTest` — startup validation of `@Policy` identifiers, details types and decision parameters, including that every shipped `@Policy` controller method declares a decision parameter matching its details type.
- `ProductDiscoveryServiceImplTest` — per-candidate evaluation, and that denied products leak nothing into the response.
- `ProductControllerTest` — the product endpoints.

Exercising the PDP end to end needs OPA running — see `docker/opa/Readme.md`. Because the PDP fails closed, a stopped OPA makes every annotated endpoint return `403`.
