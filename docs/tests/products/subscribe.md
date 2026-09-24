# Product Subscribe API: Test Requirement Specification

**Repository:** `management-node`  
**Endpoint:** `POST /api/v1/product/subscribe`  
**Role required:** `product_subscribe`  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`

---

This is the formal test specification for the **Product Subscribe** API: what the endpoint does,
what policy governs it, and every permutation a manual tester should exercise.

It is a sibling of [`product-discover.md`](product-discover.md) and [`product-view.md`](product-view.md),
and it **reuses** the setup, token recipe and "who is who" of
[`../../DISCOVERY_TEST_SCENARIOS.md`](../../DISCOVERY_TEST_SCENARIOS.md) §2, §3 and §3.1, and its
sample catalogue in §4 of that document. Read those once, then work through §3 and §4 below.

## Contents

- [1. What this API does](#1-what-this-api-does)
  - [1.1 What gets created](#11-what-gets-created)
  - [1.2 Choosing the consumer](#12-choosing-the-consumer)
  - [1.3 What policy decides](#13-what-policy-decides)
  - [1.4 Status codes](#14-status-codes)
  - [1.5 The request body](#15-the-request-body)
  - [1.6 The response](#16-the-response)
- [2. Before you start](#2-before-you-start)
- [3. Test cases](#3-test-cases)
- [4. Wales may not take topic products](#4-wales-may-not-take-topic-products)
- [5. Edge cases](#5-edge-cases)
- [6. Things that should never happen](#6-things-that-should-never-happen)
- [7. Related documents](#7-related-documents)

---

## 1. What this API does

Subscribes the calling organisation to a product, by recording a grant of that product to one of
the organisation's consumers.

- Authentication: OAuth2 JWT bearer token, `aud` including `management-node`.
- Authorization: the client role `management-node:product_subscribe`.
- Policy: `@Policy(resource = "product", action = "subscribe")`, answered by `policies.product.subscribe`.

### 1.1 What gets created

One row in `product_consumer`: the product, the consumer, when it was granted, how long it is valid
for, and the schedule. The row is what every other part of the system reads as "this organisation
has access to this product", so a successful call is a change to the configuration the federators
receive, not merely a record of a request.

**Uniqueness is per product and consumer, not per product and organisation.** That is what the
database enforces (`uq_product_consumer_pair`), and it is deliberate:

- the same consumer **cannot** be subscribed to the same product twice;
- one organisation **can** subscribe to the same product on several of its consumers, which is how
  an organisation feeds the same data to more than one system.

### 1.2 Choosing the consumer

`consumerId` is optional. When it is absent the service works out which consumer the subscription
is for, in this order:

| Step | Condition | Outcome |
|---|---|---|
| 1 | `consumerId` given | that consumer is used, **after** checking it belongs to the caller's organisation |
| 2 | the organisation has a consumer flagged `is_default` | that consumer is used |
| 3 | the organisation has exactly one consumer | that consumer is used; there is nothing to disambiguate |
| 4 | the organisation has several consumers and no default | **refused**, asking for `consumer_id` |
| 5 | the organisation has no consumers | **refused** |

An organisation has **at most one** default consumer, enforced by a partial unique index on
`consumer(org_id) WHERE is_default = TRUE`. It may have none.

### 1.3 What policy decides

Policy decides four things, and the service applies them rather than restating them:

| Term | Meaning |
|---|---|
| whether to allow at all | the refusals in §1.4, including the jurisdiction rule in §4 |
| `requires_approval` | decided by the rule, but **nothing acts on it**: see the note below |
| `max_validity_days` | the ceiling the caller's purpose allows |
| `validity_days` | the validity actually recorded, from the caller's **and the product's** attributes |

**There is no subscription status.** A grant is recorded or the request fails, and once recorded it
is in force. `product_consumer` has no status column and the configuration API does not filter on
one, so a reported status would be advisory text nothing reads back. `requires_approval` is still
decided by the rule and reported in the decision, but no approval workflow exists to act on it.

`validity_days` is the only term worked out from the product as well as the caller: how long an
organisation may hold data depends on what the data is. The product's own ceiling, first line that
describes it winning:

| Product | Ceiling |
|---|---|
| directly identifiable | 30 days |
| experimental, provisional or superseded quality | 30 days |
| pseudonymised | 90 days |
| anything else (anonymised, non-personal, validated) | 365 days |
| no attributes recorded at all | 30 days |

The grant is the **lower** of the caller's ceiling and the product's. The product can only shorten
a grant, never extend one.

### 1.4 Status codes

| Code | When | Body |
|---|---|---|
| `200` | the grant was recorded, and is in force | the subscription, with the terms policy set |
| `400` | invalid body, several consumers and none named, no consumer at all, or a consumer belonging to another organisation | error object |
| `401` | no token, or one that is invalid or expired | empty |
| `403` (role) | the token lacks `management-node:product_subscribe` | error object, **no** `reasons` |
| `403` (policy) | policy refused: `organisation.missing`, `organisation.purpose_not_permitted`, `schedule.type_not_permitted`, `jurisdiction.topic_not_permitted:…` | error object **with** `reasons` |
| `404` | no such product | error object |
| `409` | that consumer is already subscribed to that product | error object naming the consumer |

The distinction that matters: **`403` is "you may not", `409` is "that is already done".** A policy
refusal is the only one that carries `reasons`.

`message` is always the literal `"Access denied by policy"` on a policy refusal. The detail is in
`reasons`, never in `message`, so a tester checking only the message will not see which rule
refused. One reason carries its own sentence after a colon; see §4.

### 1.5 The request body

```json
{
  "productId": 3,
  "consumerId": 4,
  "scheduleType": "interval",
  "scheduleExpression": "P1D",
  "destination": "s3://bucket/prefix"
}
```

| Field | Required | Default | Notes |
|---|---|---|---|
| `productId` | **yes** | n/a | absent is a `400` |
| `consumerId` | no | the organisation's default, see §1.2 | must belong to the caller's organisation |
| `scheduleType` | no | `interval` | must be one policy permits |
| `scheduleExpression` | no | `P1D` | ISO-8601 duration for `interval`, so `P1D` is daily |
| `destination` | no | none | delivery target |

### 1.6 The response

```json
{
  "subscriptionId": 31,
  "productId": 3,
  "consumerId": 4,
  "consumerName": "ENV-CONSUMER-1",
  "grantedAt": "2026-09-22T10:15:00",
  "validityDays": 90,
  "maxValidityDays": 90,
  "scheduleType": "interval",
  "scheduleExpression": "P1D",
  "permittedScheduleTypes": ["cron", "interval"]
}
```

---

## 2. Before you start

Everything in §2 and §3 of [`../../DISCOVERY_TEST_SCENARIOS.md`](../../DISCOVERY_TEST_SCENARIOS.md)
applies. In addition:

- **OPA must be running.** The policy decision point fails closed, so a stopped OPA makes every
  request `403`. That is expected, not a defect.
- **Sample defaults.** With the sample data loaded, **ENV** has a default consumer
  (`ENV-CONSUMER-1`), and **HEG** and **BCC** each have two consumers and no default. That split is
  what lets you test both consumer-resolution paths without editing the database.
- **Subscriptions persist.** Unlike discover and view, this endpoint writes. A test that succeeds
  changes the state the next test sees, so the order below matters and repeat runs need the row
  removing first. Each case says so where it applies.
- **Never hard-code a product id.** Resolve it by name from discovery, as the sibling specifications
  instruct.
- **Product type matters here, and it is not visible in a discover response.** Since §4 refuses
  some callers by product type, you need to know which sample products are `topic` and which are
  `file`. The table in §4.1 is the reference; it is derived from
  `db/samples/V20260918150000__add_discovery_sample_dataset.sql` and the backfill in
  `db/migration/V20251013135858__add_product_type.sql`.
- **ENV is the Welsh organisation.** Its `jurisdictions` are England **and** Wales, so it is the
  sample organisation §4 catches. HEG (England, Scotland) and BCC (Bristol, England) are not.
- **Subscription does not require discoverability.** Policy does not check that the caller could
  have discovered the product, so a caller may subscribe to a product that does not appear in its
  own discover response. In practice ENV is cleared to SECRET and has a national remit, so it
  discovers the whole sample catalogue and every id below is reachable from an ENV discover.

---

## 3. Test cases

### S1: A first subscription succeeds and records the policy terms

**Objective.** The happy path writes a grant and reports the terms policy set.

**Preconditions.** ENV holds no subscription to `CoastalErosionForecast`.

**Test data.** ENV, `productId` of `CoastalErosionForecast` (ENV's own, type `file`, non-personal,
experimental), no `consumerId`.

- **Given** ENV has a default consumer and no grant on this product,
- **When** it posts `{"productId": <id>}`,
- **Then** `200`, `consumerName` is `ENV-CONSUMER-1`, `maxValidityDays` is `90`, `validityDays` is
  `30`, `scheduleType` is `interval`, `scheduleExpression` is `P1D`, and the response carries
  **no** `status` field.

> **A `file` product, deliberately.** ENV covers Wales, so it cannot subscribe to a `topic` product
> at all (§4), and every product ENV could otherwise use for a happy path is one. `validityDays` is
> `30` rather than `90` because the product is experimental, which is §1.3 doing its job.

**Why.** It proves four things at once: the default consumer was found, the defaults were applied,
the validity came from policy, and the row was written.

### S2: The same subscription again is a 409, not a second row

- **Given** S1 has run,
- **When** ENV repeats the identical request,
- **Then** `409`, and the message names the organisation, the product and the consumer already
  subscribed.
- **And** `product_consumer` still holds exactly one matching row.

### S3: The same product on a second consumer of the same organisation is allowed

- **Given** S1 has run, so `ENV-CONSUMER-1` holds the product,
- **When** ENV posts the same `productId` with `consumerId` of `ENV-CONSUMER-2`,
- **Then** `200`, and a second row exists for the same product on a different consumer.

**Why.** This is the case §1.1 exists to protect. If it returns `409`, uniqueness has been
implemented per organisation instead of per consumer.

### S4: Several consumers and no default is refused, asking for consumer_id

**Test data.** HEG, which has two consumers and no default.

- **When** HEG posts `{"productId": <id>}` with no `consumerId`,
- **Then** `400`, and the message is **"Multiple consumers found, please specify consumer_id"**.
- **And** no row was written.

### S5: Naming the consumer resolves S4

- **When** HEG repeats S4 with `consumerId` of `HEG-CONSUMER-1`,
- **Then** `200`.

### S6: A consumer belonging to another organisation is refused

- **When** ENV posts a `consumerId` belonging to a HEG consumer,
- **Then** `400`, the message says the consumer does not belong to the organisation, and no row is
  written.

**Why.** Without this check a consumer id would be a way to subscribe somebody else's consumer.

### S7: Validity is shortened by the product, not only by the caller

**Test data.** HEG (research purpose, ceiling 365 days) and a **directly identifiable** product.

- **When** HEG subscribes to `PendingPlanningApplications`,
- **Then** `200`, `maxValidityDays` is `365` **but** `validityDays` is `30`.

**Why.** The single most important case in this document: it proves the product's attributes reach
the policy engine and shorten the grant. If `validityDays` equals `maxValidityDays`, the product is
not being loaded and §1.3 is not in force.

### S8: A pseudonymised product caps the grant at 90 days

- **When** HEG subscribes to `BrownfieldLandAvailability` (pseudonymised, validated),
- **Then** `validityDays` is `90` while `maxValidityDays` is `365`.

### S9: The product can only shorten, never extend

**Test data.** ENV (purpose ceiling 90 days) and `AffordableHousingCompletions` (HEG's, type
`file`, anonymised and validated, so a product ceiling of 365). It covers England, so ENV can
discover it.

- **When** ENV subscribes to it,
- **Then** `200`, `maxValidityDays` is `90` and `validityDays` is `90`, **not** `365`.

### S10: A schedule type policy does not permit is refused

**Test data.** BCC, which has a local remit and may use `interval` only.

- **When** BCC posts `"scheduleType": "cron"`,
- **Then** `403` with `reasons` containing `schedule.type_not_permitted`.
- **And** no row is written.

### S11: The same request with an interval succeeds

- **When** BCC repeats S10 with `"scheduleType": "interval"`,
- **Then** `200`, and `permittedScheduleTypes` is `["interval"]`.

### S12: An organisation without the service delivery purpose is refused

- **When** an organisation lacking `service_delivery` subscribes,
- **Then** `403` with `reasons` containing `organisation.purpose_not_permitted`.

### S13: A token with no organisation claim is refused

- **Then** `403` with `reasons` containing `organisation.missing`.

### S14: A token without the role never reaches policy

- **When** a token lacking `product_subscribe` posts a valid body,
- **Then** `403`, the body has **no** `reasons` key, and the PDP was never called.

### S15: An unknown product is a 404

- **When** a permitted caller posts a `productId` that does not exist,
- **Then** `404`.

### S16: With policy switched off the grant is held, not accepted

**Preconditions.** `application.opa.enabled=false`.

- **When** any permitted caller subscribes,
- **Then** `200` and `validityDays` is `30`.

**Why.** Nothing judged the request, so the grant must be short rather than generous. Note that the
grant **is in force**: with no status column and no approval workflow, a subscription made while
policy is off is indistinguishable from one policy allowed. That is the reason not to run with
`application.opa.enabled=false` anywhere that matters.

---

## 4. Wales may not take topic products

An organisation whose `jurisdictions` include **Wales** may not subscribe to a product whose type
is **`topic`**, whatever else it is entitled to. The rule is in `policies.product.subscribe`, and
it is the only place jurisdiction is read on this endpoint.

Three properties of it decide what is worth testing:

| Property | Consequence |
|---|---|
| It reads the **caller's** remit, not the product's coverage | a product about England is refused just the same, if the caller covers Wales |
| Wales **alongside** other nations still counts | ENV covers England and Wales; holding England as well does not excuse it |
| It reads the **product's type**, not its owner | ENV is refused its own `topic` products |

The refusal is a policy `403` and carries one reason:

```json
{
  "status": 403,
  "message": "Access denied by policy",
  "reasons": ["jurisdiction.topic_not_permitted:Wales Juristiction not allowed to access topics"],
  "errorId": "…"
}
```

The part before the colon is the stable code to assert on; the part after it is the caller-facing
sentence. **Assert on the whole string**, including the spelling `Juristiction`, which is
deliberate and agreed — a test that quietly "corrects" it will pass against nothing.

### 4.1 Which sample products are topics

The type is not in a discover or view response, so this table is how you pick one. Anything not
listed as `topic` is safe for a Welsh caller.

| Product | Owner | Type |
|---|---|---|
| `FloodRiskMapZones` | ENV | **topic** |
| `RiverLevelTelemetry` | ENV | **topic** |
| `PollutionIncidentReports` | ENV | **topic** |
| `BrownfieldLandAvailability` | HEG | **topic** |
| `HelpToBuyApplicants` | HEG | **topic** |
| `PendingPlanningApplications` | BCC | **topic** |
| `BristolAirQualitySensors` | BCC | **topic** |
| `BristolTemporaryAccommodation` | BCC | **topic** |
| `CoastalErosionForecast`, `WaterAbstractionLicences`, `UnclassifiedDraftDataset` | ENV | file |
| `AffordableHousingCompletions`, `HousingNeedSurvey`, `EmptyHomesRegister`, `ScotlandLandRegisterExtract` | HEG | file |
| `BristolSchoolPlaces` | BCC | file |
| `Bristol_Cycle%Counts` | BCC | **none recorded** |

`FloodRiskMapZones`, `BrownfieldLandAvailability` and `PendingPlanningApplications` carry no type
in their own insert: they are typed `topic` by the backfill in
`db/migration/V20251013135858__add_product_type.sql`, which set every product existing at that
point to `topic`. So eight of the seventeen sample products are topics, and this rule puts **about
half the catalogue out of ENV's reach**. That is the rule working, not a defect — but it is why
several cases in §3 use a `file` product where an earlier version of this document used
`FloodRiskMapZones`.

### 4.2 Test cases

#### W1: A Welsh organisation is refused a topic product

**Test data.** ENV, `productId` of `RiverLevelTelemetry` (ENV's own, type `topic`).

- **Given** ENV covers England and Wales,
- **When** it posts `{"productId": <id>}`,
- **Then** `403`, `message` is `"Access denied by policy"`, and `reasons` is exactly
  `["jurisdiction.topic_not_permitted:Wales Juristiction not allowed to access topics"]`.
- **And** no row was written to `product_consumer`.

**Why.** The base case. Check the row: a refusal that still writes is the serious failure here, not
the wrong status code.

#### W2: The same caller and a file product succeeds

- **When** ENV posts `CoastalErosionForecast` (its own, type `file`) with everything else identical,
- **Then** `200`.

**Why.** Run it immediately after W1. It is what proves W1 refused on the **type** and not on
something else about ENV — without it, a broken caller gate would look like a passing W1.

#### W3: Owning the product does not excuse it

- **When** ENV posts `FloodRiskMapZones`, which ENV itself produces,
- **Then** `403` with the same reason as W1.

**Why.** The most likely misreading of the rule is that it is about whose data it is. It is not.

#### W4: A non-Welsh organisation may take a topic product

- **When** HEG (England, Scotland) posts `BrownfieldLandAvailability` (type `topic`) with
  `consumerId` of `HEG-CONSUMER-1`,
- **Then** `200`.
- **And** when BCC (Bristol, England) posts `BristolAirQualitySensors` (type `topic`) with
  `"scheduleType": "interval"` and a `consumerId`, **then** `200`.

**Why.** The rule must catch Wales and nothing else. If either of these is refused, the
jurisdiction test is matching too widely.

#### W5: A product with no type recorded is not a topic

**Test data.** `Bristol_Cycle%Counts`, the one sample product with no `product_type_id`. ENV has a
national remit rather than a local one, so its discovery is not confined to its own coverage area
and it can find this product by name as usual.

- **When** ENV posts that `productId`,
- **Then** `200`, and `validityDays` is `90`.

**Why.** "No type" must not be read as "topic". A rule written as *not `file`* rather than *is
`topic`* passes W1 to W4 and fails only here.

#### W6: The refusal is reported alongside every other reason

- **When** ENV posts a `topic` product with `"scheduleType": "daily"`, which is not a permitted
  type for anyone,
- **Then** `403`, and `reasons` contains **both**, in this order:
  `["jurisdiction.topic_not_permitted:Wales Juristiction not allowed to access topics", "schedule.type_not_permitted"]`.

**Why.** Policy reports every failing condition at once, sorted, so a caller can fix them all in
one go. A response carrying only the first is a rule that stops at the first failure.

#### W7: The terms are still returned with the refusal

- **When** W1 is repeated and the **decision** is inspected (`application.opa.log-output=true`, or
  query the PDP directly),
- **Then** `details` still carries `requires_approval: false`, `max_validity_days: 90`,
  `permitted_schedule_types: ["cron", "interval"]` and `validity_days`.

**Why.** Consistent with every other refusal on this endpoint (S10): a caller that is turned away
is still told what it would have been held to.

#### W8: Discover and view are unaffected

- **When** ENV discovers, and then views by id, a `topic` product such as `RiverLevelTelemetry`,
- **Then** both succeed as before: `200`, and the product appears in the discover results.

**Why.** The rule is on `policies.product.subscribe` alone. ENV can still **find and read** topic
products; it just cannot subscribe to them. If discover or view starts hiding them, the rule has
been put in the shared contract (`lib/product_access.rego`) by mistake, which would change two
other APIs.

#### W9: With policy switched off the rule is not enforced

**Preconditions.** `application.opa.enabled=false`.

- **When** ENV posts a `topic` product,
- **Then** `200`, and the grant is in force.

**Why.** No decision was taken, so nothing refused it. This is the same warning as S16 and is worth
repeating: with policy off there is no jurisdiction rule, and the resulting grant is
indistinguishable from one policy allowed.

---

## 5. Edge cases

| # | Case | Expected |
|---|---|---|
| E1 | `productId` absent | `400`, message names `productId` |
| E2 | `productId` not a number | `400`, never `500` |
| E3 | `consumerId` that does not exist | `400`, message says no such consumer |
| E4 | `scheduleType` absent | `200`, recorded as `interval` |
| E5 | `scheduleExpression` absent | `200`, recorded as `P1D` |
| E6 | `scheduleType` present but empty or blank | `200`, treated as absent and defaulted |
| E7 | Body is `{}` | `400` for the missing `productId` |
| E8 | No body at all | `400` |
| E9 | `destination` omitted | `200`, destination is absent from the row |
| E10 | Two identical requests sent concurrently | one `200` and one `409`, never two rows |
| E11 | ENV posts a `topic` product it is **already** subscribed to from before the rule existed | `403` from §4, not `409`; policy is judged before the duplicate check |
| E12 | A `topic` product whose `product_type` row is renamed to something else | `200`; the rule matches the type **name**, so renaming the row changes who may subscribe |
| E13 | ENV posts a `topic` product with no `consumerId` | `403` from §4; policy refuses before the consumer is resolved, so the message is the policy one, not "Multiple consumers found" |

---

## 6. Things that should never happen

| Symptom | Why it matters |
|---|---|
| Two rows for the same product and consumer | the unique constraint is not in force |
| A `409` when subscribing the same product on a **different** consumer | uniqueness applied per organisation instead of per consumer (§1.1) |
| `validityDays` equal to `maxValidityDays` for a directly identifiable product | product attributes are not reaching the policy engine (S7) |
| A row written when the response was `4xx` | the write is not inside the rejection path |
| `reasons` present on a role refusal | the role and policy refusals have been conflated |
| A subscription to another organisation's consumer succeeding | the ownership check is missing (S6) |
| a `status` field in any response | the endpoint reports no status; see §1.3 |
| ENV subscribing to any `topic` product | the §4 rule is not in force, or OPA is serving a stale policy — restart it |
| HEG or BCC refused a `topic` product | the jurisdiction test matches too widely (W4) |
| `Bristol_Cycle%Counts` refused to ENV | "no type" is being read as "topic" (W5) |
| a topic product missing from ENV's **discover** results | the rule has leaked into the shared product contract and now changes three APIs (W8) |
| the reason spelled `jurisdiction.topic_not_permitted` with no sentence after the colon | the caller-facing half of the reason has been dropped |

---

## 7. Related documents

- [Product Discover API test specification](product-discover.md): finding the product to subscribe to
- [Product View API test specification](product-view.md): reading one product
- [`../../DISCOVERY_TEST_SCENARIOS.md`](../../DISCOVERY_TEST_SCENARIOS.md): setup, tokens and the sample catalogue
- [Authentication Requirements](../../AUTHENTICATION_REQUIREMENTS.md): tokens, audiences and roles
- [Policy Enforcement](../../POLICY_ENFORCEMENT.md): how a decision is reached and what it carries
- [`docker/opa/policy_sample_stories.md`](../../../docker/opa/policy_sample_stories.md): the same
  decisions walked through per sample organisation
- `docker/opa/policies/product/subscribe.rego` and its `subscribe_test.rego`: the rule itself, and
  the Rego tests that pin every case in §4
