# Policy Sample Stories

**Repository:** `management-node`  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`  
**Status:** implemented. Every per-organisation output below is pinned by a Rego test and was checked
against the running OPA.

---

## Contents

- [Purpose](#purpose)
- [The three organisations](#the-three-organisations)
- [The products](#the-products)
- [How the rules decide, in one page](#how-the-rules-decide-in-one-page)
- [Story 1: view a product](#story-1-view-a-product)
- [Story 2: subscribe to a product](#story-2-subscribe-to-a-product)
- [Story 3: discover products](#story-3-discover-products)
- [Outcome matrix](#outcome-matrix)
- [What the rules can and cannot see](#what-the-rules-can-and-cannot-see)
- [Where the rules live](#where-the-rules-live)
- [Trying it](#trying-it)
- [Prerequisites](#prerequisites)

## Purpose

Show that one set of policy rules gives different answers to different callers, based only on the
attributes stored in the database. Users from Environment Agency (`ENV`), Homes England (`HEG`) and
Bristol City Council (`BCC`) call the same three product endpoints with the same requests. Each gets
different terms, search capabilities or visible data, and each is refused somewhere.

The rules follow three principles:

- **Only attributes decide.** No rule names an organisation or a product. Change an organisation's
  attributes in the database and its answers change, with no rule edit.
- **Every refusal says why.** Each deny carries stable reason codes, such as
  `organisation.clearance_insufficient`.
- **Missing data narrows access.** A missing or mistyped attribute gives the lowest entitlement,
  never an error and never more access.

## The three organisations

These are the `ORGANISATION` attributes in the dev database (database `postgres`, schema
`management-node`, all sample migrations applied). They reach the rules as
`input.subject.organisation.attributes`.

| Attribute | `ENV` Environment Agency | `HEG` Homes England | `BCC` Bristol City Council |
|---|---|---|---|
| `authorised_classifications` | `OFFICIAL`, `SECRET` | `OFFICIAL`, `OFFICIAL-SENSITIVE` | `OFFICIAL` |
| `permitted_purposes` | `service_delivery`, `regulatory_oversight` | `service_delivery`, `statistical_analysis` | `service_delivery` |
| `jurisdictions` | `England`, `Wales` | `England`, `Scotland` | `England`, `Bristol` |
| `department_id` | `dept-environment` | `dept-housing` | `dept-local-government` |
| `responsibility_areas` | `environmental_protection`, `flood_risk_management` | `housing_delivery`, `land_availability` | `public_health`, `urban_planning` |

The rules only read the first three rows. That gives three personas, none of which is best at
everything:

- **`ENV`** has the highest clearance (`SECRET`) and is a regulator, but has no research purpose.
- **`HEG`** is cleared to `OFFICIAL-SENSITIVE` and is the only organisation doing research
  (`statistical_analysis`).
- **`BCC`** is `OFFICIAL` only, has a single purpose, and covers a local area (`Bristol`) as well as a
  nation.

The schema also has `nationality` (`GB`, stored for `BCC` only) and a fourth organisation,
`ia-data-product-catalogue-ui`, which has no attributes. No rule reads `nationality`.

A caller whose organisation key has no attributes (`ia-data-product-catalogue-ui`, or a key that
matches no row, such as `FEDERATOR_ENV`) gets the lowest entitlement on every fact, the same as a
caller with no organisation. The only difference is the reasons: its key is present, so
`organisation.missing` is not among them.

## The products

These are the `PRODUCT` attributes in the same schema. They reach the rules as
`input.resource.attributes` during per-candidate discovery.

| id | Product | Owner | `identifiability` | `quality_designation` | `temporal_resolution` | `record_unit` | `population_risk_tags` |
|---|---|---|---|---|---|---|---|
| 1 | `BrownfieldLandAvailability` | HEG | `pseudonymised` | `validated` | `annual` | `property` | — |
| 2 | `PendingPlanningApplications` | BCC | `directly_identifiable` | `provisional` | `event_level` | `household` | `domestic_abuse_survivors`, `protected_witnesses` |
| 3 | `FloodRiskMapZones` | ENV | `non_personal` | `validated` | `daily` | `geographic_area` | — |

## How the rules decide, in one page

Every rule starts from four plain facts about the caller, worked out in `lib/entitlements.rego`:

| Fact | Meaning | `ENV` | `HEG` | `BCC` | no organisation |
|---|---|---|---|---|---|
| `clearance` | highest classification held: `OFFICIAL`=1, `OFFICIAL-SENSITIVE`=2, `SECRET`=3, `TOP SECRET`=4; unknown values are ignored | 3 | 2 | 1 | 0 |
| `has_purpose(p)` | `p` is in `permitted_purposes` | regulator | researcher | service delivery only | none |
| `uk_jurisdiction` | `jurisdictions` includes a UK nation | yes | yes | yes | no |
| `local_remit` | `jurisdictions` includes anything that is not a UK nation (`England`, `Scotland`, `Wales`, `Northern Ireland`) | no | no | **yes** (`Bristol`) | no |

Attribute values that are not strings are ignored, and a single string is read like a one-item list.
Wherever a rule says "at `SECRET`", `TOP SECRET` behaves the same.

"No organisation" in these tables means the token's `organisation` value is missing or empty. Only
then does a rule add `organisation.missing`.

Every rule has the same shape:

1. **Refusal reasons.** Each condition that fails adds a reason to `reason_set`.
2. **Verdict.** `allow` is true only when `reason_set` is empty, so a caller learns every problem at
   once.
3. **Terms.** Plain rules, each with a one-line comment, work out what the caller gets (access level,
   validity, search contract). Terms are returned on a refusal too.

The questions each fact answers:

| Fact | Questions it answers |
|---|---|
| `clearance` | Can you view a product, and how much of it? Which identifiability levels, fields and attributes can you see? How big is a page? |
| `has_purpose` | Do you need approval? How long can you subscribe for? Can you search by time resolution, or see draft data? |
| `uk_jurisdiction` | Can you use the catalogue at all? |
| `local_remit` | Which schedule types can you use? Must results be boundary-filtered? |

## Story 1: view a product

`GET /api/v1/product/{id}` is answered by `product/view.rego` (resolution `exact`).

> Product details reveal who consumes a product and on what terms, which is `OFFICIAL-SENSITIVE`
> information. You need that clearance to view a product. With `SECRET` you see everything;
> otherwise you see a summary.

| Refused when | Reason |
|---|---|
| the token has no organisation | `organisation.missing` |
| `clearance` is below `OFFICIAL-SENSITIVE` | `organisation.clearance_insufficient` |
| the method is not `GET` or `HEAD` | `action.not_read_only` |

| `details` field | `SECRET`+ | `OFFICIAL-SENSITIVE` | refused |
|---|---|---|---|
| `access_level` | `full` | `summary` | `none` |
| `withheld_fields` | `[]` | `configurations`, `consumers` | `configurations`, `consumers`, `policyAttributes`, `source` |
| `required_clearance` | `OFFICIAL-SENSITIVE` | `OFFICIAL-SENSITIVE` | `OFFICIAL-SENSITIVE` |

`access_level` is read into `ProductViewPolicyDecisionDetails`. `withheld_fields` is advisory: the
handler does not strip fields yet.

### V1: `GET /api/v1/product/1`

| Org | HTTP | `allow` | `reasons` | `access_level` | `withheld_fields` |
|---|---|---|---|---|---|
| `ENV` | 200 | `true` | `[]` | `full` | `[]` |
| `HEG` | 200 | `true` | `[]` | `summary` | `[configurations, consumers]` |
| `BCC` | 403 | `false` | `[organisation.clearance_insufficient]` | `none` | `[configurations, consumers, policyAttributes, source]` |

The refused `BCC` decision in full:

```json
{
  "allow": false,
  "reasons": ["organisation.clearance_insufficient"],
  "policy": {"id": "product.view", "version": "policies.product.view/1.0.0", "resolution": "exact"},
  "details": {"access_level": "none", "withheld_fields": ["configurations", "consumers", "policyAttributes", "source"], "required_clearance": "OFFICIAL-SENSITIVE"}
}
```

## Story 2: subscribe to a product

`POST /api/v1/product/subscribe` is answered by `product/subscribe.rego` (resolution `exact`).

> Any organisation delivering a public service may subscribe. Regulators are accepted straight away,
> and everyone else waits for approval. Researchers get the longest feeds (365 days), regulators get
> investigation-length feeds (90 days), and everyone else gets 30 days. An organisation with a local
> remit may only pull on an interval.

| Refused when | Reason |
|---|---|
| the token has no organisation | `organisation.missing` |
| `service_delivery` is not a permitted purpose | `organisation.purpose_not_permitted` |
| the body has no `productId` | `request.product_missing` (a real request gets `400` from validation first) |
| `scheduleType` is given and is not a permitted type | `schedule.type_not_permitted` |

Subscription does not check clearance or jurisdiction. Any organisation holding `service_delivery`
may subscribe, on the terms below.

| `details` field | Rule | `ENV` | `HEG` | `BCC` |
|---|---|---|---|---|
| `requires_approval` | `false` for a regulator | `false` | `true` | `true` |
| `max_validity_days` | research 365, otherwise regulator 90, otherwise 30 (an organisation holding both purposes gets 365) | 90 | 365 | 30 |
| `permitted_schedule_types` | local remit: `interval` only | `cron`, `interval` | `cron`, `interval` | `interval` |

These are read into `ProductSubscriptionPolicyDecisionDetails`, and the handler copies them into
the response.

### S1: `{"productId": 3, "scheduleType": "cron", "scheduleExpression": "0 * * * *"}`

| Org | HTTP | Response body, or reason |
|---|---|---|
| `ENV` | 200 | `{"productId": 3, "status": "ACCEPTED", "maxValidityDays": 90, "permittedScheduleTypes": ["cron", "interval"]}` |
| `HEG` | 200 | `{"productId": 3, "status": "PENDING_APPROVAL", "maxValidityDays": 365, "permittedScheduleTypes": ["cron", "interval"]}` |
| `BCC` | 403 | `schedule.type_not_permitted` |

The refused `BCC` decision still carries its terms, so the log shows what it could ask for instead:

```json
{
  "allow": false,
  "reasons": ["schedule.type_not_permitted"],
  "policy": {"id": "product.subscribe", "version": "policies.product.subscribe/2.0.0", "resolution": "exact"},
  "details": {"requires_approval": true, "max_validity_days": 30, "permitted_schedule_types": ["interval"]}
}
```

### S2: `{"productId": 3, "scheduleType": "interval", "scheduleExpression": "PT1H"}`

`ENV` and `HEG` get the same answers as in S1. `BCC` is now allowed:
`{"productId": 3, "status": "PENDING_APPROVAL", "maxValidityDays": 30, "permittedScheduleTypes": ["interval"]}`.

## Story 3: discover products

`POST /api/v1/product/discover` is answered by `product/discover.rego` (resolution `exact`).

> Discovery powers a product search. The rule does not return products; it returns the search
> contract for the caller: what they may search on, what must be masked, which products may appear,
> how big a page is, and what the search layer must also do.

### Fields and attributes

A search layer handles the two differently, so the contract describes them separately:

| | Fields | Attributes |
|---|---|---|
| What they are | properties of the product itself, as the API names them | policy attributes stored against a product in `policy_attribute_value` |
| Examples | `name`, `topic`, `type`, `source`, `consumers` | `identifiability`, `quality_designation`, `population_risk_tags` |
| How a search filters them | on the product's own columns | by joining the attribute table |
| Where they are listed | `lib/product.rego`, `fields`: `id`, `producerId`, `name`, `topic`, `type`, `source`, `consumers`, `configurations`, `policyAttributes` | everything else a caller names |

A name in the request's `filters` counts as a field when it is in the field list, and as an attribute
otherwise. Two consequences:

- **Some fields can never be filtered on.** Only `name`, `topic`, `type` and (with enough clearance)
  `source` are allowed. Filtering on `id`, `producerId`, `consumers`, `configurations` or
  `policyAttributes` is refused for everyone with `filter.field_not_permitted`.
- **An unknown name is refused as an attribute.** A name that is neither a field nor an allowed
  attribute, such as `department_id` or a typo, is refused with `filter.attribute_not_permitted`.

### The contract

| `details` field | Rule | `ENV` | `HEG` | `BCC` |
|---|---|---|---|---|
| `allowed_filtered_fields` | `name`, `topic`, `type`; plus `source` if you may see it | `name`, `source`, `topic`, `type` | `name`, `source`, `topic`, `type` | `name`, `topic`, `type` |
| `allowed_filtered_attributes` | `quality_designation`, `record_unit`; + `temporal_resolution` for research; + `identifiability` at `OFFICIAL-SENSITIVE`; + `population_risk_tags` at `SECRET` | `identifiability`, `population_risk_tags`, `quality_designation`, `record_unit` | `identifiability`, `quality_designation`, `record_unit`, `temporal_resolution` | `quality_designation`, `record_unit` |
| `denied_filtered_fields` | fields asked for but not allowed | per request | per request | per request |
| `denied_filtered_attributes` | attributes asked for but not allowed | per request | per request | per request |
| `masked_filtered_fields` | by clearance, as view withholds them from an allowed caller: none at `SECRET`; `configurations`, `consumers` at `OFFICIAL-SENSITIVE`; also `policyAttributes`, `source` below that | `[]` | `configurations`, `consumers` | `configurations`, `consumers`, `policyAttributes`, `source` |
| `masked_filtered_attributes` | sensitive attributes (`population_risk_tags`) below `SECRET` | `[]` | `population_risk_tags` | `population_risk_tags` |
| `row_filter` | which products may appear; see below | widest | middle | narrowest |
| `max_page_size` | 100 at `SECRET`, 50 at `OFFICIAL-SENSITIVE`, 20 below | 100 | 50 | 20 |
| `obligations` | `audit_access` always; `mask_response` if anything is masked; `aggregate_before_release` for research; `apply_boundary_filter` for a local remit | `audit_access` | `aggregate_before_release`, `audit_access`, `mask_response` | `apply_boundary_filter`, `audit_access`, `mask_response` |
| `evaluation` | `request`, or `candidate` when judging one product | | | |

`row_filter` combines up to three conditions on product attributes, using the proof of concept's node
shape plus a `none_of` operator (a multi-valued attribute sharing no value with a list):

| Condition | `ENV` | `HEG` | `BCC` |
|---|---|---|---|
| `identifiability in`: by clearance, 1 → `anonymised`, `non_personal`; 2 → + `pseudonymised`; 3 → + `directly_identifiable` | all four | + `pseudonymised` | `anonymised`, `non_personal` |
| `quality_designation in`: `validated`; + `provisional` for a regulator; + `provisional`, `experimental` for research | `provisional`, `validated` | `experimental`, `provisional`, `validated` | `validated` |
| `population_risk_tags none_of` all risk tags, below `SECRET` | not applied | applied | applied |

A refused search gets `row_filter: {"type": "literal", "value": false}`: no rows, never all rows.

### Refusals

| Refused when | Reason |
|---|---|
| the token has no organisation | `organisation.missing` |
| no UK nation in `jurisdictions` | `organisation.jurisdiction_not_permitted` |
| no classification held | `organisation.clearance_missing` |
| a requested filter is a field you may not use | `filter.field_not_permitted` |
| a requested filter is an attribute you may not use | `filter.attribute_not_permitted` |

Asking for a filter you may not use refuses the search rather than dropping the filter. Dropping it
would return more than was asked for and hide the refusal.

Per candidate (`input.resource.id` set), the product is also judged against `row_filter`:

| Refused when | Reason |
|---|---|
| the product lacks `identifiability` or `quality_designation` | `product.attributes_missing` |
| its `identifiability` is not permitted | `product.identifiability_not_permitted` |
| its `quality_designation` is not permitted | `product.quality_not_permitted` |
| it carries a risk tag you may not see | `product.population_risk_not_permitted` |

### D1: `{"text": "land"}`, a plain search

All three are allowed. `ENV`'s full decision:

```json
{
  "allow": true,
  "reasons": [],
  "policy": {"id": "product.discover", "version": "policies.product.discover/2.0.0", "resolution": "exact"},
  "details": {
    "evaluation": "request",
    "allowed_filtered_fields": ["name", "source", "topic", "type"],
    "denied_filtered_fields": [],
    "masked_filtered_fields": [],
    "allowed_filtered_attributes": ["identifiability", "population_risk_tags", "quality_designation", "record_unit"],
    "denied_filtered_attributes": [],
    "masked_filtered_attributes": [],
    "row_filter": {"type": "group", "combinator": "and", "nodes": [
      {"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised", "directly_identifiable", "non_personal", "pseudonymised"]},
      {"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": ["provisional", "validated"]}
    ]},
    "max_page_size": 100,
    "obligations": ["audit_access"]
  }
}
```

`HEG` and `BCC` differ exactly as the contract table says. `BCC`'s:

```json
"details": {
  "evaluation": "request",
  "allowed_filtered_fields": ["name", "topic", "type"],
  "denied_filtered_fields": [],
  "masked_filtered_fields": ["configurations", "consumers", "policyAttributes", "source"],
  "allowed_filtered_attributes": ["quality_designation", "record_unit"],
  "denied_filtered_attributes": [],
  "masked_filtered_attributes": ["population_risk_tags"],
  "row_filter": {"type": "group", "combinator": "and", "nodes": [
    {"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised", "non_personal"]},
    {"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": ["validated"]},
    {"type": "comparison", "attribute": "population_risk_tags", "operator": "none_of", "values": ["children", "domestic_abuse_survivors", "protected_witnesses", "rare_condition_cohorts"]}
  ]},
  "max_page_size": 20,
  "obligations": ["apply_boundary_filter", "audit_access", "mask_response"]
}
```

**Products each contract admits.** This is what a search applying `row_filter` returns, and what
per-candidate evaluation returns:

| Product | `ENV` | `HEG` | `BCC` |
|---|---|---|---|
| 1 `BrownfieldLandAvailability` | ✅ | ✅ | ❌ `product.identifiability_not_permitted` |
| 2 `PendingPlanningApplications` | ✅ | ❌ `product.identifiability_not_permitted`, `product.population_risk_not_permitted` | ❌ `product.identifiability_not_permitted`, `product.population_risk_not_permitted`, `product.quality_not_permitted` |
| 3 `FloodRiskMapZones` | ✅ | ✅ | ✅ |

### D2 to D6: searching with filters

A refused search is HTTP `403`. Its decision shows the denied names and still lists what was allowed.

| Request `filters` | `ENV` | `HEG` | `BCC` |
|---|---|---|---|
| D2 `{"identifiability": "pseudonymised"}` | ✅ | ✅ | ❌ attributes `[identifiability]` |
| D3 `{"population_risk_tags": "children", "temporal_resolution": "annual"}` | ❌ attributes `[temporal_resolution]` | ❌ attributes `[population_risk_tags]` | ❌ attributes `[population_risk_tags, temporal_resolution]` |
| D4 `{"population_risk_tags": "children"}` | ✅ | ❌ attributes `[population_risk_tags]` | ❌ attributes `[population_risk_tags]` |
| D5 `{"temporal_resolution": "annual"}` | ❌ attributes `[temporal_resolution]` | ✅ | ❌ attributes `[temporal_resolution]` |
| D6 `{"source": "x", "topic": "y"}` | ✅ | ✅ | ❌ fields `[source]` |

D3 refuses all three, each for a different gap: `ENV` is not a researcher, `HEG` is not cleared to
`SECRET`, and `BCC` is neither. D6 shows a field refusal: `BCC` may not see `source`, so it may not
search on it either.

## Outcome matrix

| Call | `ENV` | `HEG` | `BCC` |
|---|---|---|---|
| V1 view product | ✅ full | ✅ summary | ❌ `organisation.clearance_insufficient` |
| S1 subscribe, `cron` | ✅ accepted, 90 days | ✅ pending approval, 365 days | ❌ `schedule.type_not_permitted` |
| S2 subscribe, `interval` | ✅ accepted, 90 days | ✅ pending approval, 365 days | ✅ pending approval, 30 days |
| D1 discover | ✅ 4 fields + 4 attributes, page 100, 3 products | ✅ 4 fields + 4 attributes, page 50, 2 products, masked | ✅ 3 fields + 2 attributes, page 20, 1 product, masked |
| D2 filter `identifiability` | ✅ | ✅ | ❌ `filter.attribute_not_permitted` |
| D3 filter risk tags + time | ❌ `filter.attribute_not_permitted` | ❌ `filter.attribute_not_permitted` | ❌ `filter.attribute_not_permitted` |
| D4 filter risk tags | ✅ | ❌ `filter.attribute_not_permitted` | ❌ `filter.attribute_not_permitted` |
| D5 filter time resolution | ❌ `filter.attribute_not_permitted` | ✅ | ❌ `filter.attribute_not_permitted` |
| D6 filter `source`, `topic` | ✅ | ✅ | ❌ `filter.field_not_permitted` |

Each organisation's refusals and limits trace back to its own attributes:

| Org | Refused | Limited |
|---|---|---|
| `ENV` | time-resolution search (D3, D5): not a researcher | 90-day subscriptions; no experimental products |
| `HEG` | risk-tag search (D3, D4): not cleared to `SECRET` | summary view; approval needed; no directly identifiable products; consumer details masked |
| `BCC` | view (V1): only `OFFICIAL`; `cron` (S1): local remit; most searches | 30-day interval-only subscriptions needing approval; one product visible; most data masked |

## What the rules can and cannot see

| Endpoint | Organisation attributes | Product attributes | Request body |
|---|---|---|---|
| view | yes | no (only the id, in `request.path`) | no |
| subscribe | yes | no | yes |
| discover, request level | yes | no | yes (`text`, `filters`) |
| discover, per candidate | yes | yes | the bound DTO |

As a result:

- **View and subscribe decide on who is asking, not which product.** Product 1 and product 3 get the
  same answer.
- **Discovery varies by product through `row_filter`.**
- **Per-candidate evaluation exists in the rule but is not called yet.** The discover handler is not
  wired to `ProductDiscoveryService`, so today only the request-level contract is produced.
- **A refused caller only sees `403 "Access denied by policy"`.** Reasons and details appear in the
  service log when `application.opa.log-output` is on (it is in the `dev` profile).

## Where the rules live

| File | Contains |
|---|---|
| `policies/lib/entitlements.rego` | the four facts: `clearance`, `has_purpose`, `uk_jurisdiction`, `local_remit` |
| `policies/lib/product.rego` | product `fields`, `hidden_fields` by clearance, `sensitive_attributes` |
| `policies/product/view.rego` | Story 1 |
| `policies/product/subscribe.rego` | Story 2 |
| `policies/product/discover.rego` | Story 3 |
| `policies/product/fallback.rego` | every other product action, such as `browse` (routed in `routing/data.json`): read-only for any caller with an organisation key, `details: {"access_level": "read"}`; these rules do not use the entitlement facts |
| `policies/lib/decision.rego` | `organisation_known` (the key is a non-empty string) and the deny-shaped starting document |
| `policies/lib/sample_data_test.rego` | the attributes above, exactly as stored, used by every test |
| `policies/lib/entitlements_test.rego`, `policies/product/*_test.rego` | tests that pin the per-organisation outcomes in this document |

On the Java side, `ProductDiscoveryPolicyDecisionDetails` reads the six lists as `fields()` and
`attributes()`. When request and candidate decisions combine, each is merged so that withholding
wins. The rest of the contract (`row_filter`, `max_page_size`, `obligations`) is read through
`additional()`.

## Trying it

Run the tests from `docker/opa`:

```bash
P="$PWD/policies:/p:ro"
docker run --rm -v "$P" openpolicyagent/opa:1.20.2 test /p
```

Ask the running OPA directly. This is `BCC` searching on `identifiability` (D2); it is refused:

```bash
curl -s localhost:8181/v1/data/dispatch/decision -H 'content-type: application/json' -d '{
  "input": {
    "subject": {"organisation": {"key": "BCC", "attributes": {
      "authorised_classifications": ["OFFICIAL"], "permitted_purposes": ["service_delivery"],
      "jurisdictions": ["Bristol", "England"]}}},
    "action": "discover", "resource": {"kind": "product"},
    "request": {"method": "POST", "body": {"filters": {"identifiability": "pseudonymised"}}}
  }}' | jq .result
```

Change `authorised_classifications` to `["OFFICIAL", "OFFICIAL-SENSITIVE"]` and the same search is
allowed.

## Prerequisites

To see the outputs through the service, not just from OPA:

1. **The caller's `organisation` must be `ENV`, `HEG` or `BCC`.**
   - **How the value reaches the rules:** the `MANAGEMENT_NODE_ACCESS` client scope maps the user's
     `organisation` attribute into the access token and the introspection response.
     `KeycloakJwtAuthenticationConverter` reads it from introspection, falling back to the token.
     `PolicyInputFactory` then matches it against `organisation.organisation_key`.
   - **Values stored in the local realm:**

     | Keycloak user | `organisation` | Matches a key? |
     |---|---|---|
     | `nikannegaresh` | `BCC` | yes |
     | `service-account-federator_env` | `FEDERATOR_ENV` | **no** |
     | `service-account-federator_heg` | `FEDERATOR_HEG` | **no** |
     | `service-account-federator_bcc` | `FEDERATOR_BCC` | **no** |

   - **What to do:** add an `ENV` user and a `HEG` user, or set the service accounts' attribute to the
     bare key. A value that matches no key arrives with no attributes and is refused by all
     three rules: view (`organisation.clearance_insufficient`), subscribe
     (`organisation.purpose_not_permitted`) and discover (`organisation.clearance_missing`,
     `organisation.jurisdiction_not_permitted`).
2. **Keycloak's token endpoint needs a valid client certificate.** The ones under `docker/` and
   `docker/cert/` expired in July 2026.
3. **Run the service with the `dev` profile.** It turns on `application.opa.enabled` and
   `log-output`, and uses the `management-node` schema.
4. **Restart OPA after changing a rule:** `docker compose restart opa` in `docker/opa`.
5. **The caller holds `product_view`, `product_subscribe` and `product_discovery`.** Without the
   role, the call is refused before policy runs.
