# Product View API: Test Requirement Specification

**Repository:** `management-node`
**Endpoint:** `GET /api/v1/product/{productId}` (`ProductController.viewProduct`)
**Role required:** `product_view`
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`

---

This is the formal test specification for the **Product View** API: what the endpoint does, what
policy governs it, and every permutation a manual tester should exercise.

It is a **sibling** of [`product-discover.md`](product-discover.md) and a formal counterpart to the
informal walkthrough in [`../../DISCOVERY_TEST_SCENARIOS.md`](../../DISCOVERY_TEST_SCENARIOS.md)
§5a (scenarios S17–S22). Those scenarios are a guided tour; this document is the checklist. It
**reuses** that document's setup (§2), token recipe and "who is who" (§3, §3.1), and sample
catalogue (§4) rather than repeating them; read §2–§4 there once, then work through §3 below.

## Contents

- [1. What this API does](#1-what-this-api-does)
  - [1.1 The one thing to understand: view is discover, constrained to one product](#11-the-one-thing-to-understand-view-is-discover-constrained-to-one-product)
  - [1.2 What policy decides](#12-what-policy-decides)
  - [1.3 Status codes](#13-status-codes)
  - [1.4 The response](#14-the-response)
  - [1.5 `access_level` is reported, not enforced](#15-access_level-is-reported-not-enforced)
- [2. Before you start](#2-before-you-start)
  - [2.1 Environment](#21-environment)
  - [2.2 Never hardcode a product id](#22-never-hardcode-a-product-id)
  - [2.3 Helpers used throughout](#23-helpers-used-throughout)
  - [2.4 Who is who, and what each caller should see](#24-who-is-who-and-what-each-caller-should-see)
- [3. Test cases](#3-test-cases)
- [4. Edge cases](#4-edge-cases)
- [5. Things that should never happen](#5-things-that-should-never-happen)
- [6. Related documents](#6-related-documents)

---

## 1. What this API does

`GET /api/v1/product/{productId}` returns **one data product, as much of it as policy permits**.

- Authentication: OAuth2 JWT bearer token, `aud` including `management-node`.
- Authorization: the client role `management-node:product_view`.
- Policy: `@Policy(resource = "product", action = "view")`, answered by the Rego module
  `policies.product.view` (`docker/opa/policies/product/view.rego`).
- No request body, no query parameters. Exactly one id, matched exactly.
- Response: `DiscoveredProductDTO`, the **whole** product object model, which is the same type
  discovery returns.

### 1.1 The one thing to understand: view is discover, constrained to one product

**View is a replica of discover.** There are exactly two differences:

| | `POST /api/v1/product/discover` | `GET /api/v1/product/{productId}` |
|---|---|---|
| how many products | a page of them | exactly one, by id |
| how much of each | a **summary** (`id`, `name`, `description`, `type`, and the owning `organisation`'s `key` and `name`) | the **whole** object model |

Everything else is shared, and shared **from one definition** rather than two that happen to agree:
`docker/opa/policies/lib/product_access.rego` holds the caller gates, the refusal reasons, the row
filter and the masking, and both `policies.product.discover` and `policies.product.view` are that
module. On the Java side both endpoints go through the same `ProductQueryPlanner`,
`ProductSearchQueryBuilder`, `ProductDiscoveryRepository` and `DiscoveredProductAssembler`; the view
service adds one condition, `id = :productId`, AND-ed onto the policy's row filter.

Two consequences, and they are what most of this document tests:

1. **If you can discover a product, you can view it. If you cannot discover it, view answers
   `404`.** A product id is not a way around discovery's filtering.
2. **A product excluded by policy and a product that does not exist are deliberately
   indistinguishable**: both `404`, with identical (empty) bodies.

> **Why `404` and not `403` for a withheld product.** A `403` there would turn an id into a probe
> for what exists: `403` would mean "it exists and is not yours", `404` "it does not exist", and a
> caller could enumerate the catalogue one id at a time. The caller is already allowed to *ask*,
> that is what the role check and the caller-level policy check decide, so the per-product answer
> must not distinguish the two. This is a non-disclosure guarantee, not an oversight, and it has a
> test case of its own ([V5](#v5--the-two-404s-are-indistinguishable)).

### 1.2 What policy decides

Policy controls three separate things. Keeping them apart makes most surprises self-explaining.

| Policy controls | Mechanism | Effect when it says no | How you see it |
|---|---|---|---|
| whether this **caller** may view at all | the three caller gates | the request is refused before any product is looked at | `403 "Access denied by policy"` with `reasons` |
| which products **exist** for this caller | `row_filter`, the query's `WHERE` clause | the product is not found | `404`, empty body |
| what is **shown** of the product | `masked_filtered_fields`, `mask_sensitive_attributes`, `unmask_when` | that member is absent from the JSON | the key is simply not there |

**The three caller gates are exactly discover's three**, from the shared module:

| Reason | Meaning |
|---|---|
| `organisation.missing` | the token carried no `organisation` claim, so the calling organisation could not be named |
| `organisation.jurisdiction_not_permitted` | the organisation's `jurisdictions` include no UK nation |
| `organisation.clearance_missing` | the organisation holds no `authorised_classifications` at all, not even `OFFICIAL` |

There is **no clearance floor on view**. `OFFICIAL` is enough, so Bristol City Council (BCC), the
lowest-cleared sample organisation, is allowed to view. What a clearance costs a caller is
**fields, not the endpoint**.

There is **no method gate** on the view rule either: the endpoint is a `@GetMapping`, so nothing
but `GET` (and `HEAD`) can reach the rule.

### 1.3 Status codes

| Code | When | Body |
|---|---|---|
| `200` | the caller may view the product and it exists | the product, with withheld members absent |
| `400` | `{productId}` is not a whole number | `{"status":400,"message":"Invalid value for 'productId'","errorId":"…"}` |
| `401` | no token, or an invalid/expired one | **empty**, answered by the security filter chain, with a `WWW-Authenticate: Bearer` header |
| `403` (role) | the token lacks `management-node:product_view` | `{"status":403,"message":"Access denied: insufficient permissions for this operation","errorId":"…"}`, with **no `reasons` key** |
| `403` (policy) | a caller-level gate refused: `organisation.missing`, `organisation.jurisdiction_not_permitted`, `organisation.clearance_missing` | `{"status":403,"message":"Access denied by policy","reasons":[…],"errorId":"…"}` |
| `404` | the product does not exist **or** the row filter excludes it | **empty** |
| `500` | never, for anything in this document | n/a |

The two `403`s are answered by different layers and are told apart by the **message** and by the
presence of `reasons`. Enforcement order is: authentication → role → policy → handler, and a
request refused at one step never reaches the next, so a caller without the role is never sent to
the PDP at all.

### 1.4 The response

`200` returns the **full** object model, as far as policy permits:

```json
{
  "id": 3,
  "name": "FloodRiskMapZones",
  "description": "Flood risk zones for England and Wales …",
  "topic": "topic.FloodRiskMapZones",
  "type": "topic",
  "source": "kafka://…",
  "attributes": { "identifiability": "non_personal", "quality_designation": "validated" },
  "organisation": {
    "key": "ENV",
    "name": "Environment Agency",
    "attributes": { "jurisdictions": ["England", "Wales"], "authorised_classifications": ["OFFICIAL", "SECRET"] }
  },
  "producer": {
    "name": "ENV-PRODUCER-1",
    "description": "…",
    "active": true,
    "attributes": { }
  },
  "consumers": [
    {
      "name": "HEG-CONSUMER-1",
      "organisation": { "key": "HEG", "name": "Homes England" },
      "attributes": { },
      "subscription": {
        "grantedAt": "2026-02-11T07:00:00",
        "validity": 365,
        "scheduleType": "cron",
        "scheduleExpression": "0 0 * * *",
        "attributes": { }
      }
    }
  ],
  "subscribedBy": [
    { "key": "HEG", "name": "Homes England", "consumers": 1, "since": "2026-02-11T07:00:00" }
  ]
}
```

Rules that hold for every `200`:

- **A withheld member is absent: never `null`, never `[]`, never `{}`.** The DTO is
  `@JsonInclude(NON_NULL)`, and more importantly a withheld member is **never read from the
  database** in the first place: masking is applied by not selecting the column and not running the
  block's query.
- **`id` is always present.** It is what you pass to `GET /{productId}` and to
  `POST /api/v1/product/subscribe`.
- **Producer connection details are never returned at all**: `host`, `port`, `tls` and
  `idp_client_id` are not members of the model, for any caller, at any clearance.
- **A subscription's `destination` is never returned at all**, for the same reason.
- A nested `consumers[].organisation` carries `key` and `name` only; it never carries `attributes`.
- Attribute maps keep their JSON types: a multi-valued attribute is an array, a number stays a
  number.
- The response carries **no `policy` block**. Discovery's response has one (it is how a UI builds a
  filter panel); view returns the bare product.

What is masked depends only on the caller's clearance, and is the same list discovery applies:

| Caller | clearance | masked fields |
|---|---|---|
| **ENV** | SECRET | *(nothing)* |
| **HEG** | OFFICIAL-SENSITIVE | `consumers` |
| **BCC** | OFFICIAL | `consumers`, `policyAttributes`, `source`, `subscribedBy` |

`policyAttributes` is the block name for the **product's own** `attributes` map. The
`organisation.attributes` and `producer.attributes` maps are governed separately, by
`mask_sensitive_attributes` (§[V8](#v8--sensitive-attributes-are-withheld-below-secret-in-every-attributes-map)).

**One exception, and it is important:** an organisation always sees `consumers` and `subscribedBy`
on **its own** products, even when those members are masked for it in general. The rule ships an
`unmask_when` clause for exactly that, evaluated per product by the database.

### 1.5 `access_level` is reported, not enforced

The decision's `details.access_level` reports the tier the caller was granted:

| Value | When |
|---|---|
| `full` | clearance SECRET or above |
| `summary` | clearance OFFICIAL-SENSITIVE |
| `basic` | clearance OFFICIAL |
| `none` | the request was refused |

**Nothing branches on it.** It does not appear in the response body, and it does not change what is
returned; what is actually withheld is the masking lists. Two mechanisms for "show less" is how
they come to disagree, so there is only one, and `access_level` is carried so a client can tell the
caller which tier it holds and so the log records it.

Do not expect a body to change because `access_level` changed. You can see the value in the
service log (`Product view policy decision … accessLevel=…` at `DEBUG`, and
`Product view audit … accessLevel=…` at `INFO`), or in the PDP decision log when
`application.opa.log-output` is on.

---

## 2. Before you start

### 2.1 Environment

Follow [`DISCOVERY_TEST_SCENARIOS.md` §2](../../DISCOVERY_TEST_SCENARIOS.md#2-setting-up) exactly:
Keycloak + Postgres + OPA up, the `product_view` role assigned, an `organisation` claim on each
federator client, the sample data loaded (17 products), and the application running over mTLS on
`https://localhost:8090`.

Two things that will otherwise waste an afternoon:

- **OPA fails closed.** If OPA is not running, every view request returns `403`. Check
  `curl -s http://localhost:8181/health` before reporting anything.
- **The organisation comes from the token, not from the request.** Getting the same answer for all
  three callers almost always means you sent the same token three times. See
  [§3.1 "Who is who"](../../DISCOVERY_TEST_SCENARIOS.md#31-who-is-who).

Unless a test case says otherwise, run with policy enforcement **on**:

```yaml
application:
  opa:
    enabled: true          # or OPA_ENABLED=true
```

### 2.2 Never hardcode a product id

> **Product ids differ between environments.** They are PostgreSQL sequence values: a freshly
> migrated database, a re-seeded one, and the environment the next tester uses will not agree on
> which id is `FloodRiskMapZones`. An id that is `3` for you may be `20` for someone else, or may
> not exist at all.

**Always take the id from a discover response first.** That is also the natural workflow this
endpoint is designed for: discover returns summaries carrying `id`, and the caller opens one.

```bash
# the id of a named product, for this caller, in this environment
id_of() {   # id_of <TOKEN> <product name>
  discover "$1" "{\"text\":\"$2\"}" | jq -r --arg n "$2" '.products[] | select(.name == $n) | .id'
}
```

Every test case below names products, never ids, and tells you which caller to resolve the id as.

### 2.3 Helpers used throughout

```bash
get_token() {   # get_token <client id>
  curl -sk --cert client.crt --key client.key \
    'https://localhost:8443/realms/mng-node/protocol/openid-connect/token' \
    -d "client_id=$1" -d 'grant_type=client_credentials' | jq -r .access_token
}

discover() {    # discover <TOKEN> '<json body>'
  curl -sk --cert client.crt --key client.key \
    -X POST 'https://localhost:8090/api/v1/product/discover' \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "${2:-\{\}}"
}

view() {        # view <TOKEN> <productId>  -> status code only
  curl -sk --cert client.crt --key client.key -o /dev/null -w '%{http_code}\n' \
    "https://localhost:8090/api/v1/product/$2" -H "Authorization: Bearer $1"
}

view_body() {   # view_body <TOKEN> <productId>  -> body
  curl -sk --cert client.crt --key client.key \
    "https://localhost:8090/api/v1/product/$2" -H "Authorization: Bearer $1"
}

view_full() {   # view_full <TOKEN> <productId>  -> status, headers and body
  curl -sk --cert client.crt --key client.key -i \
    "https://localhost:8090/api/v1/product/$2" -H "Authorization: Bearer $1"
}

ENV_TOKEN=$(get_token FEDERATOR_ENV)
HEG_TOKEN=$(get_token FEDERATOR_HEG)
BCC_TOKEN=$(get_token FEDERATOR_BCC)
```

### 2.4 Who is who, and what each caller should see

From [`DISCOVERY_TEST_SCENARIOS.md` §4](../../DISCOVERY_TEST_SCENARIOS.md#4-the-sample-catalogue),
the attributes below are the only reason the three callers differ.

| | ENV (Environment Agency) | HEG (Homes England) | BCC (Bristol City Council) |
|---|---|---|---|
| `organisation` claim | `ENV` | `HEG` | `BCC` |
| clearance | **SECRET** | **OFFICIAL-SENSITIVE** | **OFFICIAL** |
| purposes | service delivery, regulatory oversight | service delivery, statistical analysis | service delivery |
| jurisdictions | England, Wales | England, Scotland | England, **Bristol** (local remit) |
| owns | 6 products | 6 products | 5 products |
| **discovers / views** | **15** of 17 | **12** of 17 | **8** of 17 |
| `access_level` reported | `full` | `summary` | `basic` |
| masked fields | *(none)* | `consumers` | `consumers`, `policyAttributes`, `source`, `subscribedBy` |
| `mask_sensitive_attributes` | `false` | `true` | `true` |

> These counts were measured against the real policy and a real database. **Every organisation
> views exactly what it discovers: ENV 15, HEG 12, BCC 8.** If your numbers differ, work through
> [`DISCOVERY_TEST_SCENARIOS.md` §0](../../DISCOVERY_TEST_SCENARIOS.md#0-if-all-three-organisations-return-the-same-products)
> before reporting a bug; the usual cause is one identity sent three times.

The 17 products and their attributes are tabulated in
[`DISCOVERY_TEST_SCENARIOS.md` §4.2](../../DISCOVERY_TEST_SCENARIOS.md#42-the-17-products). Four
are deliberately awkward and are used by the cases below:

| Product | Owner | Why it is useful here |
|---|---|---|
| `FloodRiskMapZones` | ENV | discoverable by **all three**; the masking comparison ([V6](#v6--masking-per-organisation)) |
| `PendingPlanningApplications` | BCC | directly identifiable **and** risk-tagged; only BCC sees it, and only because it owns it ([V7](#v7--an-organisation-sees-its-own-products-unmasked)) |
| `HelpToBuyApplicants` | HEG | carries the **sensitive** attribute `population_risk_tags` ([V8](#v8--sensitive-attributes-are-withheld-below-secret-in-every-attributes-map)) |
| `ScotlandLandRegisterExtract` | HEG | harmless data BCC still cannot reach; jurisdiction alone ([V3](#v3--a-product-the-caller-cannot-discover-returns-404)) |
| `Bristol_Cycle%Counts` | BCC | **no description and no product type** ([E7](#e7--a-product-with-a-null-description-or-no-product-type)) |

---

## 3. Test cases

Each case states what it proves, what must be true first, the data to use, the steps, and **why**,
so that when an answer differs you can tell a bug from a data change.

---

### V1: A discoverable product returns 200 with the full model

**Objective.** Confirm the happy path for each of the three callers, and that the response is the
whole object model rather than a discovery summary.

**Preconditions.** Policy on; OPA healthy; token carries `product_view` and an `organisation` claim.

**Test data.** `FloodRiskMapZones` (ENV's, discoverable by all three). Resolve its id **as the
caller under test**.

**Given–When–Then.**

```bash
for t in "$ENV_TOKEN" "$HEG_TOKEN" "$BCC_TOKEN"; do
  id=$(id_of "$t" FloodRiskMapZones)
  echo "id=$id status=$(view "$t" "$id")"
  view_body "$t" "$id" | jq 'keys'
done
```

- **Given** a caller whose token carries `product_view` and an `organisation` claim,
- **When** it requests a product it can discover,
- **Then** the response is `200 application/json`, the body is a single JSON object (not a list, not
  a page), `id` equals the id requested, and `name`, `description`, `topic`, `type` and
  `organisation` are present for every caller.
- **And** the body carries members a discover result does not: `producer` for every caller, plus
  `consumers` / `subscribedBy` / `attributes` where the caller's clearance permits ([V6](#v6--masking-per-organisation)).
- **And** there is **no** `page` object and **no** `policy` object.

**Why.** This is the endpoint's contract: one product, whole model, `200`. `producer` appearing here
and never in a discover result is the clearest single signal that you are looking at the view
projection (`ProductProjection.FULL`) and not a summary.

---

### V2: You can view exactly what you can discover

**Objective.** Prove the central guarantee across a caller's **whole** catalogue, not one product.

**Preconditions.** Policy on. Run it for each of the three callers.

**Test data.** Every id in the caller's discover result, and every id in ENV's larger catalogue that
the caller's result does not contain.

**Given–When–Then.**

```bash
T="$BCC_TOKEN"          # repeat with $HEG_TOKEN and $ENV_TOKEN

# 1. every id this caller discovers must be 200
mine=$(discover "$T" '{}' | jq -r '.products[].id' | sort -n)
for id in $mine; do echo "discoverable $id -> $(view "$T" "$id")"; done

# 2. every id it does NOT discover must be 404 - take them from ENV's larger catalogue
for id in $(discover "$ENV_TOKEN" '{}' | jq -r '.products[].id' | sort -n); do
  grep -qx "$id" <<<"$mine" || echo "NOT discoverable $id -> $(view "$T" "$id")"
done
```

- **Given** the set of products a caller discovers,
- **When** each id in that set is viewed, **Then** every one returns `200`.
- **And when** each id outside that set is viewed, **Then** every one returns `404`.
- **Expected totals:** ENV 15 × `200`, HEG 12 × `200`, BCC 8 × `200`. In loop 2, BCC should produce
  `404` for the 7 ids ENV discovers that BCC does not.

**Why.** A single `200` in loop 2 is a serious defect: it would mean an id is a way around
discovery's filtering, which is exactly the hole this endpoint's row filter exists to close. A
`404` in loop 1 is equally a defect in the other direction: "discoverable but not viewable" is
incoherent, since both come from one Rego definition.

---

### V3: A product the caller cannot discover returns 404

**Objective.** The row filter, on one named product, so a failure is diagnosable rather than a count.

**Preconditions.** Policy on.

**Test data.** `ScotlandLandRegisterExtract` (HEG's). Resolve its id **as HEG**, since BCC cannot
discover it, so BCC cannot resolve it.

**Given–When–Then.**

```bash
id=$(id_of "$HEG_TOKEN" ScotlandLandRegisterExtract)
view "$HEG_TOKEN" "$id"     # its owner
view "$ENV_TOKEN" "$id"     # England+Wales, national remit
view "$BCC_TOKEN" "$id"     # local remit: Bristol and England
```

- **Given** a product covering Scotland only,
- **When** HEG (its owner, and covering Scotland) views it, **Then** `200`.
- **When** ENV views it, **Then** `200`, since ENV has a national remit, so no jurisdiction condition is
  applied to it.
- **When** BCC views it, **Then** `404` with an **empty body**, since BCC has a local remit, and the
  product's coverage does not overlap Bristol or England.

**Why.** Nothing about this product is sensitive: it is anonymised and validated. It is withheld
from BCC by **jurisdiction alone**, which makes it the cleanest proof that the row filter, not the
clearance gate, is what produces the `404`. If BCC gets `200`, the row filter is not reaching the
view query.

---

### V4: A non-existent id returns 404

**Objective.** Baseline for [V5](#v5--the-two-404s-are-indistinguishable).

**Preconditions.** Policy on. Confirm the id really does not exist:
`SELECT count(*) FROM mn.product WHERE id = 999999;` → `0`.

**Test data.** `999999`.

**Given–When–Then.**

```bash
view_full "$BCC_TOKEN" 999999
```

- **Given** an id no product has,
- **When** any authorised caller views it,
- **Then** `404`, with an **empty body** (`Content-Length: 0`) and no JSON.

**Why.** The endpoint's own `404` is the framework's bare `notFound()`, not an error document. That
matters for the next case: the two `404`s can only be identical if neither carries a body that
could differ.

---

### V5: The two 404s are indistinguishable

**Objective.** The non-disclosure guarantee: a caller cannot tell "withheld" from "does not exist".

**Preconditions.** Policy on; [V3](#v3--a-product-the-caller-cannot-discover-returns-404) and
[V4](#v4--a-non-existent-id-returns-404) both pass.

**Test data.** As BCC: the id of `ScotlandLandRegisterExtract` (exists, withheld) and `999999`
(does not exist).

**Given–When–Then.**

```bash
withheld=$(id_of "$HEG_TOKEN" ScotlandLandRegisterExtract)

view_full "$BCC_TOKEN" "$withheld" > /tmp/withheld.txt
view_full "$BCC_TOKEN" 999999      > /tmp/missing.txt

diff /tmp/withheld.txt /tmp/missing.txt      # expect: no differences except Date/traceparent-style headers
```

- **Given** one id that exists but is excluded by the caller's row filter, and one that does not
  exist at all,
- **When** the same caller views both,
- **Then** both return `404`, both bodies are empty, and the responses differ in nothing the caller
  could key on: no `reasons`, no `errorId`, no differing message, no differing content type, and
  **no difference in status**.
- **And** neither is a `403`.

**Why.** This is a deliberate design decision (`product_view_requirement.md` §6.3, decision **D1**),
not an oversight. A `403` for the withheld one would mean "it exists and is not yours", making an id
an oracle for the existence of products the caller may not discover. The caller was already allowed
to *ask*, since the role check and the caller-level policy check decide that, so the per-product answer
must reveal nothing further. **Any difference between these two responses is a disclosure bug and
should be raised at high severity.**

> The distinction *is* recorded, where it belongs: the service logs
> `Product view audit … productId=… found=false` for both, and only the operator can see which was
> which.

---

### V6: Masking per organisation

**Objective.** Confirm that what each clearance withholds is exactly what discovery withholds.

**Preconditions.** Policy on.

**Test data.** `FloodRiskMapZones`, discoverable by all three, and owned by ENV, so the
own-product unmasking of [V7](#v7--an-organisation-sees-its-own-products-unmasked) does **not**
apply to HEG or BCC here.

**Given–When–Then.**

```bash
for t in "$ENV_TOKEN" "$HEG_TOKEN" "$BCC_TOKEN"; do
  id=$(id_of "$t" FloodRiskMapZones)
  view_body "$t" "$id" | jq '{
      has_source:       has("source"),
      has_attributes:   has("attributes"),
      has_consumers:    has("consumers"),
      has_subscribedBy: has("subscribedBy"),
      has_producer:     has("producer"),
      has_organisation: has("organisation")
  }'
done
```

- **Given** one product and three callers of different clearance,
- **When** each views it,
- **Then** the members present are exactly:

| Member | ENV (SECRET) | HEG (OFFICIAL-SENSITIVE) | BCC (OFFICIAL) |
|---|---|---|---|
| `id`, `name`, `description`, `topic`, `type` | present | present | present |
| `organisation` (`key`, `name`) | present | present | present |
| `producer` (`name`, `description`, `active`) | present | present | present |
| `source` | present | present | **absent** |
| `attributes` (the product's own) | present | present | **absent** |
| `consumers` | present | **absent** | **absent** |
| `subscribedBy` | present | present | **absent** |

- **And** every absent member is **absent**, not `null`, not `[]`, not `{}`. `jq 'has("source")'`
  must be `false`, never `true` with a null value.

**Why.** These are the `hidden_fields` of `lib/product.rego`, applied identically to both endpoints.
Compare this table with
[`DISCOVERY_TEST_SCENARIOS.md` S2](../../DISCOVERY_TEST_SCENARIOS.md#s2--the-same-product-seen-differently):
it is the same table, which is the point. Anyone who may see a product may see **whose** it is:
`organisation` and `producer` are in nobody's masked list.

> `organisation.attributes` and `producer.attributes` are governed separately, by sensitivity rather
> than by the masked-field list; see [V8](#v8--sensitive-attributes-are-withheld-below-secret-in-every-attributes-map).

---

### V7: An organisation sees its own products unmasked

**Objective.** The `unmask_when` rule: a member masked for a caller in general is still shown on
that caller's **own** products.

**Preconditions.** Policy on.

**Test data.** `PendingPlanningApplications`, BCC's own, directly identifiable, tagged
`domestic_abuse_survivors` and `protected_witnesses`. Only BCC can reach it, and only because it
owns it. Compare with `FloodRiskMapZones`, which BCC can also view but does not own.

**Given–When–Then.**

```bash
own=$(id_of "$BCC_TOKEN" PendingPlanningApplications)
other=$(id_of "$BCC_TOKEN" FloodRiskMapZones)

view_body "$BCC_TOKEN" "$own"   | jq '{name, has_consumers: has("consumers"), has_subscribedBy: has("subscribedBy"), has_source: has("source"), has_attributes: has("attributes")}'
view_body "$BCC_TOKEN" "$other" | jq '{name, has_consumers: has("consumers"), has_subscribedBy: has("subscribedBy")}'
```

- **Given** BCC, for which `consumers` and `subscribedBy` are masked in general ([V6](#v6--masking-per-organisation)),
- **When** it views **its own** product,
- **Then** `200`, and `consumers` and `subscribedBy` are **present**.
- **And** `source` and `attributes` remain **absent** even on its own product; those two are masked
  outright and are not part of the unmask rule.
- **And when** the same caller views a product it does not own, **Then** `consumers` and
  `subscribedBy` are absent again, in the same session, with the same token.

**Why.** `unmask_when` names `["subscribedBy", "consumers"]` with the condition
"`organisation.key` equals the caller's own", and the database evaluates it **per product** in the
same query that finds them. This is also why BCC reaches this product at all: the row filter admits
an organisation's own products as a first branch, whatever their attributes say. If the own product
shows nothing extra, the unmask column is not being applied; if the non-owned product also shows
`consumers`, the masking is not being applied at all.

---

### V8: Sensitive attributes are withheld below SECRET, in every attributes map

**Objective.** `mask_sensitive_attributes` applies to **all** attribute maps (product,
organisation, producer, consumer and subscription) and is driven by the attribute catalogue, not
by a list in the rule.

**Preconditions.** Policy on. The sample data flags three attribute definitions `sensitive`:
`population_risk_tags` (product scope), `authorised_classifications` and `permitted_purposes`
(organisation scope). Confirm with:

```sql
SELECT namespace, name, sensitive FROM mn.policy_attribute_definition WHERE sensitive = TRUE;
```

**Test data.** `HelpToBuyApplicants`, HEG's own, carrying `population_risk_tags: ["children"]`.
Viewable by ENV (SECRET) and by HEG (its owner).

**Given–When–Then.**

```bash
id_env=$(id_of "$ENV_TOKEN" HelpToBuyApplicants)
id_heg=$(id_of "$HEG_TOKEN" HelpToBuyApplicants)

view_body "$ENV_TOKEN" "$id_env" | jq '{product: (.attributes|keys), org: (.organisation.attributes|keys)}'
view_body "$HEG_TOKEN" "$id_heg" | jq '{product: (.attributes|keys), org: (.organisation.attributes|keys)}'
```

- **Given** an attribute whose definition is flagged `sensitive`,
- **When** ENV (SECRET, `mask_sensitive_attributes: false`) views the product,
- **Then** `attributes` **contains** `population_risk_tags`, and `organisation.attributes`
  **contains** `authorised_classifications` and `permitted_purposes`.
- **When** HEG (OFFICIAL-SENSITIVE, `mask_sensitive_attributes: true`) views the same product,
- **Then** `attributes` is present (HEG does not mask `policyAttributes`) but **does not contain**
  `population_risk_tags`; and `organisation.attributes` **does not contain**
  `authorised_classifications` or `permitted_purposes`, while non-sensitive ones such as
  `jurisdictions` remain.
- **And** the same holds for `producer.attributes`, `consumers[].attributes` and
  `consumers[].subscription.attributes` where those blocks are visible to the caller.

**Why.** Which attributes are sensitive is **data** (`policy_attribute_definition.sensitive`); the
rule only says whether *this* caller has them withheld. Flagging a new attribute sensitive therefore
takes effect on the next request with no Rego and no Java change, so this case is also the
regression test for that. A sensitive name appearing for HEG means the sensitivity flag is not
reaching the attribute query.

> Useful contrast: BCC has `policyAttributes` masked outright, so its product `attributes` map is
> absent entirely ([V6](#v6--masking-per-organisation)), a stronger withholding than sensitivity
> filtering, and it applies before it.

---

### V9: A viewed product agrees with the same product in a discover result

**Objective.** The two endpoints return the same object model, so the members they share must match
exactly, value for value, not merely "look similar".

**Preconditions.** Policy on. Run for each caller.

**Test data.** Any product the caller discovers; do it for several.

**Given–When–Then.**

```bash
T="$HEG_TOKEN"
discover "$T" '{}' | jq -c '.products[]' | while read -r summary; do
  id=$(jq -r .id <<<"$summary")
  viewed=$(view_body "$T" "$id")
  jq -n --argjson a "$summary" --argjson b "$viewed" '
    def shared($x): {
      id:          $x.id,
      name:        $x.name,
      description: $x.description,
      type:        $x.type,
      orgKey:      $x.organisation.key,
      orgName:     $x.organisation.name
    };
    {id: $a.id, same: (shared($a) == shared($b))}'
done
```

Every line must report `"same": true`.

- **Given** a product returned in a discover page,
- **When** the same product is viewed by the same caller,
- **Then** every member the summary carries (`id`, `name`, `description`, `type`,
  `organisation.key`, `organisation.name`) has an identical value in the viewed object.
  (Compare those members individually, not the whole `organisation` object: the view adds
  `organisation.attributes`, which the summary deliberately omits.)
- **And** the viewed object carries **strictly more**: `topic`, `producer`, and whatever of
  `source`, `attributes`, `consumers`, `subscribedBy` and the attribute maps the caller's clearance
  permits.
- **And** the viewed object never carries **less** than the summary.

**Why.** One object model, one assembler, one set of masking rules. A divergence here means the two
endpoints have grown separate paths, which is the failure mode the shared design exists to
prevent. Note that `organisation.attributes` being absent from the *summary* and present in the
*view* is **not** a divergence: a search deliberately carries the organisation block without its
attributes, to avoid an extra statement per page.

---

### V10: `access_level` is reported, not enforced

**Objective.** Stop a tester expecting the response body to change with `access_level`.

**Preconditions.** Policy on, and `application.opa.log-output: true` (or the service log at `DEBUG`
for `uk.gov.dbt.ndtp.ia.node.management`).

**Test data.** `FloodRiskMapZones`, viewed by each caller.

**Given–When–Then.**

- **Given** three callers at three clearances,
- **When** each views the same product and you read the decision from the log,
- **Then** the decision's `details.access_level` is `full` for ENV, `summary` for HEG, `basic` for
  BCC.
- **And** `access_level` appears **nowhere in the response body**; `jq 'has("access_level")'` is
  `false` and `jq 'has("accessLevel")'` is `false`.
- **And** the difference in what each body contains is explained entirely by the masked-field lists
  of [V6](#v6--masking-per-organisation), not by `access_level`.
- **And** for a refused caller ([V14](#v14--caller-level-policy-403-organisationmissing)), the
  decision reports `access_level: "none"` and the response is a `403`, not a reduced `200`.

**Why.** Decision **D4** of the design: `access_level` was retired as a control and kept as a
report, because two mechanisms for "show less" is how they come to disagree. It is carried so a
client can tell the caller which tier it holds. Treat "the body did not change when access_level
changed" as **correct**.

---

### V11: A non-numeric id returns 400

**Objective.** Type conversion happens before policy, and reports the parameter, not the value.

**Preconditions.** A valid token with the `product_view` role.

**Test data.** `abc`.

**Given–When–Then.**

```bash
curl -sk --cert client.crt --key client.key -w '\n%{http_code}\n' \
  'https://localhost:8090/api/v1/product/abc' -H "Authorization: Bearer $ENV_TOKEN"
```

- **Given** a path variable that is not a whole number,
- **When** the request is made,
- **Then** `400`, body
  `{"status":400,"message":"Invalid value for 'productId'","errorId":"…"}`.
- **And** the message names the **parameter**, never the value the caller sent, and never the
  handler or the exception.
- **And** no PDP decision is logged for this request.

**Why.** Spring's type conversion refuses the request before the handler is invoked, so policy never
sees it. A `403` or `500` here would mean the request got further than it should. Note this is one
of the few cases where the caller learns something specific, and it is safe, because the caller
supplied the value.

---

### V12: No token returns 401

**Objective.** Authentication is step one; nothing below it runs.

**Preconditions.** None beyond a running service.

**Test data.** A real product id (any), and no `Authorization` header.

**Given–When–Then.**

```bash
id=$(id_of "$ENV_TOKEN" FloodRiskMapZones)
curl -sk --cert client.crt --key client.key -i "https://localhost:8090/api/v1/product/$id"
```

- **Given** no bearer token (repeat with a malformed token and with an expired one),
- **When** the request is made,
- **Then** `401`, with a `WWW-Authenticate: Bearer` header and **no JSON body**, from the security
  filter chain answers, not the application's exception handler.
- **And** the response is `401` whether or not the id exists: an unauthenticated caller learns
  nothing about the catalogue.

**Why.** Enforcement order. A `403` here would mean the token was accepted; a `404` would mean the
handler ran without authentication.

---

### V13: A token without the `product_view` role returns 403 (role), not a policy 403

**Objective.** Distinguish the role refusal from the policy refusal; they look alike and are
diagnosed differently.

**Preconditions.** A client whose service account holds `product_discovery` but **not**
`product_view`. Easiest in the Keycloak admin console: on `FEDERATOR_BCC`'s service account, remove
the `product_view` client role, take a fresh token, and restore it afterwards.

**Test data.** A product the caller *can* discover, so the only thing missing is the role.

**Given–When–Then.**

```bash
NO_VIEW=$(get_token FEDERATOR_BCC)          # after removing the role
echo "$NO_VIEW" | cut -d. -f2 | base64 -d 2>/dev/null | jq '.resource_access."management-node".roles'
id=$(id_of "$NO_VIEW" FloodRiskMapZones)    # discovery still works
view_body "$NO_VIEW" "$id" | jq
```

- **Given** a valid token without `management-node:product_view`,
- **When** the product is viewed,
- **Then** `403` with
  `{"status":403,"message":"Access denied: insufficient permissions for this operation","errorId":"…"}`.
- **And** the body carries **no `reasons` key at all**; that field appears only on policy refusals.
- **And** no decision is sent to the PDP: nothing appears in the OPA decision log, and no
  `Product view policy decision` line is written.

**Why.** The role check runs **before** policy, so a caller without the role is never judged by
policy. The two `403`s are told apart by the message and by the presence of `reasons`; confusing
them sends a tester looking at Rego when the fix is in Keycloak. Contrast with
[V14](#v14--caller-level-policy-403-organisationmissing).

---

### V14: Caller-level policy 403: `organisation.missing`

**Objective.** The first caller gate, and the practical way to trigger it.

**Preconditions.** A token carrying `product_view` but **no** `organisation` claim. In the Keycloak
admin console: on the federator client's dedicated scope, disable the hardcoded `organisation`
claim mapper, take a fresh token, and re-enable it afterwards. Verify:

```bash
echo "$NO_ORG" | cut -d. -f2 | base64 -d 2>/dev/null | jq '{organisation, roles: .resource_access."management-node".roles}'
# organisation must be null; roles must include product_view
```

**Test data.** Any id, existing or not; the answer must be the same.

**Given–When–Then.**

```bash
view_body "$NO_ORG" 1      | jq
view_body "$NO_ORG" 999999 | jq
```

- **Given** an authenticated caller holding the role but no organisation,
- **When** any product is viewed,
- **Then** `403` with
  `{"status":403,"message":"Access denied by policy","reasons":["organisation.missing"],"errorId":"…"}`.
- **And** the same `403` is returned for an id that exists and for one that does not; no product is
  looked at, so nothing about the catalogue is revealed.
- **And** `POST /api/v1/product/discover` with the same token is refused with the **same reason**.

**Why.** The gate is caller-level: it is decided before any product is considered, which is why it
is a `403` and not a `404`. `EnhancedPrincipal` substitutes an internal sentinel when the claim is
missing, and that sentinel is deliberately **not** forwarded to the PDP, so the rule sees a null
key and reports `organisation.missing`. That discover reports the same reason is the shared
definition working.

---

### V15: Caller-level policy 403: jurisdiction and clearance

**Objective.** The other two caller gates, and that they are the same three discover applies.

**Preconditions.** A token whose `organisation` claim is a non-empty string that matches **no**
`organisation.organisation_key` row, for example `NOSUCH`. Set the hardcoded claim mapper's value
to `NOSUCH` in Keycloak, take a fresh token, restore afterwards.

**Test data.** Any id.

**Given–When–Then.**

```bash
view_body "$UNKNOWN_ORG" 1 | jq
discover  "$UNKNOWN_ORG" '{}' | jq       # same reasons
```

- **Given** an organisation key that resolves to no row, so the caller has **no attributes at all**,
- **When** any product is viewed,
- **Then** `403 "Access denied by policy"` whose `reasons` contain **both**
  `organisation.jurisdiction_not_permitted` (no UK nation in its jurisdictions) **and**
  `organisation.clearance_missing` (no authorised classifications).
- **And** `reasons` does **not** contain `organisation.missing`; the key was present, just unknown.
- **And** discover with the same token returns the same reasons.
- **And** the reasons are **sorted and de-duplicated**: every failing condition is reported at once,
  so a caller can fix them all together.

An equivalent, data-driven way to provoke each gate separately, if you prefer changing the database
to changing Keycloak:

| To provoke | Soft-delete, for that organisation |
|---|---|
| `organisation.clearance_missing` | its `authorised_classifications` values in `policy_attribute_value` |
| `organisation.jurisdiction_not_permitted` | its `jurisdictions` values (or replace them with a non-UK value) |

Set `is_deleted = TRUE` rather than deleting, and restore afterwards; attributes are read live per
request, so the change takes effect on the next call with no restart.

**Why.** These three gates live in `lib/product_access.rego` and are shared verbatim between the two
endpoints. A gate that refuses view but not discover (or the reverse) is a defect, not a policy
choice; `view_test.rego` asserts the two decisions' reasons are identical for every sample
organisation.

---

### V16: Policy switched off: every real id returns 200, nothing masked

**Objective.** The master switch is a first-class supported mode, and view must work in it, not
fail, and not quietly return nothing.

**Preconditions.** Restart with `OPA_ENABLED=false`. OPA may be stopped as well; nothing should call
it. At startup the log must carry the documented warning that policy will **not** be evaluated.

**Test data.** Ids that exist (take them from any caller's discover, which now returns all 17) and
one that does not.

**Given–When–Then.**

```bash
OPA_ENABLED=false ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

discover "$BCC_TOKEN" '{}' | jq '.page.totalElements'      # expect 17
for id in $(discover "$BCC_TOKEN" '{}' | jq -r '.products[].id'); do
  echo "$id -> $(view "$BCC_TOKEN" "$id")"
done
view "$BCC_TOKEN" 999999
view_body "$BCC_TOKEN" "$(id_of "$BCC_TOKEN" FloodRiskMapZones)" | jq 'keys'
```

- **Given** `application.opa.enabled=false`,
- **When** any authorised caller views any existing product,
- **Then** `200`, **including** products policy would have withheld, for **every** caller.
- **And** nothing is masked: `source`, `attributes`, `consumers`, `subscribedBy`,
  `organisation.attributes` and `producer.attributes` are all present for BCC, and sensitive
  attributes are **not** withheld.
- **And** `404` is returned only for ids that genuinely do not exist.
- **And** no decision is sent to the PDP, and no `Product view audit` line is written (the
  `audit_access` obligation comes from the decision, and there is none).
- **Finally**, switch the policy back on and confirm the differences return.

**Why.** With the switch off no decision is taken at all; the handler receives an empty `Optional`
and the read runs with the open contract. This is documented behaviour, not a bug, but it means
**any environment relying on policy must have the switch on**. Verify the startup warning is present
so an operator cannot miss it.

> The opposite case is deliberately *not* permissive: if the switch is **on** and a decision still
> does not reach the handler, the request is refused with `403` and `policy.enforcement_missing`
> logged at `ERROR`. "Policy is on but silently not applied" cannot present as a successful view.

---

### V17: Connection details and delivery destinations are never returned

**Objective.** Two things are outside the model entirely, for every caller at every clearance; this
is not masking, it is absence by design.

**Preconditions.** Policy on.

**Test data.** A product with at least one consumer, viewed by **ENV** (SECRET, the caller that
sees the most).

**Given–When–Then.**

```bash
id=$(id_of "$ENV_TOKEN" BristolAirQualitySensors)
view_body "$ENV_TOKEN" "$id" | jq '{
  producer_keys: (.producer | keys),
  subscription_keys: ([.consumers[].subscription | keys] | add | unique),
  consumer_org_keys: ([.consumers[].organisation | keys] | add | unique)
}'
```

- **Given** the highest-cleared caller, viewing a product whose consumers it can see,
- **When** the response is inspected,
- **Then** `producer` carries exactly `name`, `description`, `active`, `attributes`, and **no**
  `host`, `port`, `tls` or `idpClientId`.
- **And** no `consumers[].subscription` carries a `destination`.
- **And** `consumers[].organisation` carries `key` and `name` only, never `attributes`.

**Why.** These are deliberately not part of the product model, so no clearance and no policy change
can reveal them. If one appears, the DTO or the projection has changed, and the change needs
reviewing on disclosure grounds: a `200` looks entirely normal while carrying it.

---

### V18: The audit obligation is honoured

**Objective.** Policy's `audit_access` obligation produces one audit line per view, and it is the
only place where a withheld product and a missing one are distinguishable.

**Preconditions.** Policy on; service log at `INFO`.

**Test data.** One `200`, one policy-withheld `404`, one non-existent `404`; reuse
[V3](#v3--a-product-the-caller-cannot-discover-returns-404) and
[V4](#v4--a-non-existent-id-returns-404).

**Given–When–Then.**

- **Given** a decision carrying the `audit_access` obligation (every allowed decision does),
- **When** each of the three requests is made,
- **Then** the log carries one line per request of the form
  `Product view audit policy={id=product.view, version=policies.product.view/3.0.0} productId=<id> found=<true|false> accessLevel=<…>`.
- **And** `found=true` for the `200`, `found=false` for **both** `404`s.
- **And** the audit line records the **product id asked for**, never any product content.
- **And** an obligation the service does not recognise would refuse the request with `403`; see
  `DiscoveryObligations`.

**Why.** XACML's rule: a Policy Enforcement Point that cannot fulfil an obligation must not grant.
The audit line is also the operator's only way to tell the two `404`s apart, which is exactly the
distinction the response must not make ([V5](#v5--the-two-404s-are-indistinguishable)).

---

## 4. Edge cases

All of these use an authenticated caller holding `product_view`, with policy **on**, unless stated.
None of them may ever produce a `500`.

### E1: Id `0`

```bash
view "$ENV_TOKEN" 0
```

`0` is a valid `Long`, so it binds and the query runs. **Expect `404`, empty body.** No sequence
issues `0`, so nothing matches. A `400` here would mean something is validating the range (nothing
does); a `500` would mean the id is not being bound as a parameter.

### E2: Negative ids

```bash
view "$ENV_TOKEN" -1
view "$ENV_TOKEN" -999999
```

**Expect `404`** for both, empty body. Negative values are valid `Long`s and are bound like any
other. **Why it matters:** the id is compiled as an ordinary field comparison with a bound
parameter, so a negative number is simply a value that matches nothing, and it must not reach the SQL
as text, and must not trip a parse error.

### E3: Very large ids

| Request | Expect | Why |
|---|---|---|
| `GET /api/v1/product/9223372036854775807` | `404` | `Long.MAX_VALUE` is a valid `Long`; no product has it |
| `GET /api/v1/product/9223372036854775808` | `400`, `"Invalid value for 'productId'"` | one past `Long.MAX_VALUE`; conversion fails before the handler |
| `GET /api/v1/product/99999999999999999999` | `400`, same message | as above |

The boundary is the only thing being tested; a `500` on either side is a defect.

### E4: A product that exists but whose owning organisation the caller cannot reach

Take an id from ENV's catalogue that BCC does not discover, for instance `HousingNeedSurvey`
(HEG's, `experimental`; resolve it **as HEG**, since ENV does not discover it either).

```bash
id=$(id_of "$HEG_TOKEN" HousingNeedSurvey)
view "$HEG_TOKEN" "$id"    # owner: 200
view "$ENV_TOKEN" "$id"    # 404 - quality 'experimental' is not one ENV's purposes earn
view "$BCC_TOKEN" "$id"    # 404
```

**Expect `200` for HEG, `404` for ENV and BCC.** This is worth its own case because it shows the
`404` is **not** about clearance: ENV is the most-cleared caller in the sample and still gets `404`.
Clearance, purpose and jurisdiction each independently shape the row filter.

### E5: `HEAD` versus `GET`

```bash
curl -sk --cert client.crt --key client.key -I \
  "https://localhost:8090/api/v1/product/$id" -H "Authorization: Bearer $ENV_TOKEN"
```

**Expect** the same status `GET` would give (`200` / `403` / `404`), with headers and **no body**.
Spring MVC serves `HEAD` from the `@GetMapping` handler, and the view rule has no method gate, so
`HEAD` is judged exactly as `GET` is. **Importantly, `HEAD` must not be a way to probe existence:**
a withheld product must return `404` to `HEAD` just as it does to `GET`. Check this explicitly
against a product the caller cannot discover.

### E6: Trailing slash and missing id

| Request | Expect |
|---|---|
| `GET /api/v1/product/{id}/` | `404` with a **JSON** body, `{"status":404,"message":"Resource not found: …","errorId":"…"}`, refused by routing, not by the handler |
| `GET /api/v1/product/` | `404` (or `405` if the framework matches the collection path) with a JSON body |
| `GET /api/v1/product` | `404` or `405` with a JSON body |

The distinction that matters: a routing `404` carries a JSON error document, while the endpoint's
own `404` is **empty**. Do not mistake one for the other when comparing responses in
[V5](#v5--the-two-404s-are-indistinguishable); compare only responses to well-formed requests.

### E7: A product with a NULL description or no product type

`Bristol_Cycle%Counts` (BCC's) has no description and no product type.

```bash
id=$(id_of "$BCC_TOKEN" 'Bristol_Cycle%Counts')     # or find it by listing BCC's catalogue
view_body "$BCC_TOKEN" "$id" | jq '{name, has_description: has("description"), has_type: has("type"), has_topic: has("topic")}'
```

**Expect `200`**, with `description` and `type` **absent**: not `null`, not `""`. `name` and
`topic` are present. **Why:** `@JsonInclude(NON_NULL)` means an unrecorded value and a withheld one
look the same in the JSON, which is deliberate; neither must produce a `null` key or an error. Note
the `%` and `_` in the name are LIKE metacharacters; they matter to discovery's text search, not to
view, but this is the product to use if you want to confirm they cause no trouble on the way in.

### E8: An id with whitespace, a sign, or a decimal point

| Request (URL-encoded as shown) | Expect | Note |
|---|---|---|
| `/api/v1/product/3.0` | `400`, `"Invalid value for 'productId'"` | a decimal is not a `Long` |
| `/api/v1/product/%203` (leading space) | `200` **or** `400`; either is acceptable, **never `500`** | Spring's number conversion trims surrounding whitespace, so this most likely binds as `3`; confirm the behaviour in your environment and record it |
| `/api/v1/product/+3` | `200` **or** `400`, never `500` | as above |
| `/api/v1/product/0003` | `200` for the product with id `3` | leading zeros are insignificant to `Long` parsing |
| `/api/v1/product/3%00` | `400`, never `500` | a null byte must not reach the query |

The requirement under test is narrow: **the id is bound as a number or the request is refused with
`400`.** It must never be interpolated into SQL, and it must never produce a `500`.

### E9: Repeatability and caching

```bash
for i in 1 2 3; do view "$BCC_TOKEN" "$withheld_id"; done
```

**Expect `404` every time.** Then view a product the caller *can* see, and re-view the withheld one:
still `404`. A decision is taken **per request** and nothing is cached across them, so no sequence of
calls may turn a `404` into a `200`.

---

## 5. Things that should never happen

Raise any of these immediately; each is a disclosure or an integrity failure rather than a
behavioural difference.

| Symptom | Why it is serious |
|---|---|
| a `200` for a product the same caller cannot discover | an id is a way around discovery's filtering, the exact hole the row filter closes |
| a `403` (rather than `404`) for a product excluded by the row filter | turns an id into a probe for what exists |
| any observable difference between the two `404`s of [V5](#v5--the-two-404s-are-indistinguishable) | the same disclosure, by another route |
| a masked member present as `null`, `[]` or `{}` rather than absent | masking is being applied after reading, not by not reading, and the value reached the JVM |
| `host`, `port`, `tls`, `idpClientId` or a subscription `destination` in any response | outside the model by design |
| a sensitive attribute present for a caller below SECRET | the catalogue's `sensitive` flag is not reaching the attribute query |
| discover and view disagreeing for the same caller and product | the two rules have drifted, which one shared Rego module exists to prevent |
| a `500` for any input in [§4](#4-edge-cases) | an unhandled conversion or query failure |
| a successful view while policy enforcement is on but no decision was taken | must be `403` with `policy.enforcement_missing` |

---

## 6. Related documents

- [`docs/tests/products/product-discover.md`](product-discover.md): the sibling specification for
  `POST /api/v1/product/discover`. Read alongside this one: the two endpoints share their gates,
  row filter and masking, so a discovery defect usually shows here too.
- [`docs/DISCOVERY_TEST_SCENARIOS.md`](../../DISCOVERY_TEST_SCENARIOS.md): setup (§2), tokens and
  who is who (§3), the sample catalogue (§4), the discovery walkthrough (§5), and the informal view
  scenarios S17–S22 (§5a).
- [`docs/POLICY_ENFORCEMENT.md`](../../POLICY_ENFORCEMENT.md): how a decision is requested, the
  decision document, the master switch, and the `GET /api/v1/product/{productId}` reference section.
- [`docs/AUTHENTICATION_REQUIREMENTS.md`](../../AUTHENTICATION_REQUIREMENTS.md): token structure
  and the role required per endpoint.
- [`docs/DATABASE_SCHEMA.md`](../../DATABASE_SCHEMA.md): the product, organisation and
  `policy_attribute_*` tables, and the `policy_attribute_live_value` view the queries read.
- `docker/opa/policies/product/view.rego` and `docker/opa/policies/lib/product_access.rego`: the
  policy itself; `docker/opa/policy_sample_stories.md` walks the sample organisations through it.
- `product_view_requirement.md` (repository root): the design, its status codes (§6.3) and the
  decisions behind them (§8), including **D1** (`404`, not `403`) and **D2** (the response type).
