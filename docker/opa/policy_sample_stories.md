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
  `organisation.clearance_missing`.
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

These are the `PRODUCT` attributes in the same schema. A product's **owner** is the organisation
offering it, resolved `product → producer → organisation`; `coverage_jurisdictions` says where its
data applies, and is what a caller with a local remit is matched against.

| id | Product | Owner | `identifiability` | `quality_designation` | `temporal_resolution` | `record_unit` | `population_risk_tags` | `coverage_jurisdictions` |
|---|---|---|---|---|---|---|---|---|
| 1 | `BrownfieldLandAvailability` | HEG | `pseudonymised` | `validated` | `annual` | `property` | none | England |
| 2 | `PendingPlanningApplications` | BCC | `directly_identifiable` | `provisional` | `event_level` | `household` | `domestic_abuse_survivors`, `protected_witnesses` | Bristol |
| 3 | `FloodRiskMapZones` | ENV | `non_personal` | `validated` | `daily` | `geographic_area` | none | England, Wales |

`db/samples/V20260918150000__add_discovery_sample_dataset.sql` adds fourteen more (ids 4–17) for
exercising discovery end to end: every identifiability and quality combination, products with
missing attributes, a Scotland-only one and a name carrying `LIKE` metacharacters. All seventeen
are in `policies/lib/sample_data_test.rego`, with their owners, and Story 3's product table below
covers them all.

## How the rules decide, in one page

Every rule starts from four plain facts about the caller, worked out in `lib/entitlements.rego`:

| Fact | Meaning | `ENV` | `HEG` | `BCC` | no organisation |
|---|---|---|---|---|---|
| cleared to | the highest classification held; unknown values are ignored. The rules ask `cleared_to_secret`, `cleared_to_official_sensitive` and so on - each meaning "at least this" - never a number | `SECRET` | `OFFICIAL-SENSITIVE` | `OFFICIAL` | none |
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
| what you are cleared to | Can you view a product, and how much of it? Which identifiability levels, fields and attributes can you see? How big is a page? |
| `has_purpose` | Do you need approval? How long can you subscribe for? Can you search by time resolution, or see draft data? |
| `uk_jurisdiction` | Can you use the catalogue at all? |
| `local_remit` | Which schedule types can you use? Which areas' products can you discover? |

## Story 1: view a product

`GET /api/v1/product/{id}` is answered by `product/view.rego` (resolution `exact`), version
`policies.product.view/3.0.0`.

> A view is a search that returns one product, so the rule **is** the discovery rule: the same
> gates, the same reasons and the same contract, all of them `lib/product_access.rego`'s. The
> service AND-s `id eq {productId}` onto the `row_filter` it returns and answers `404` when nothing
> comes back. The only difference between the two decisions is the one key each rule adds for
> itself: discovery's `evaluation`, view's `access_level`.

| Refused when | Reason | HTTP |
|---|---|---|
| the token has no organisation | `organisation.missing` | 403 |
| the organisation's remit includes no UK nation | `organisation.jurisdiction_not_permitted` | 403 |
| the organisation holds no classification at all | `organisation.clearance_missing` | 403 |
| the product does not exist, **or** `row_filter` excludes it | none (the rule was not asked about the product) | 404 |

The `404` is deliberate: a caller cannot tell a product it may not see from one that does not
exist, exactly as in discovery. A `403` there would mean "it exists and is not yours".

**View has no gate of its own, and that is the point.** It once had two, and both have gone. A
clearance floor of `OFFICIAL-SENSITIVE` refused `BCC` (`organisation.clearance_insufficient`) the
very products it could already discover, its own included, which is what an endpoint-specific
near-duplicate does as soon as the two rules drift. A method check (`action.not_read_only`) was the
last asymmetry: discovery is a `POST` and has none, the endpoint is a `@GetMapping` so nothing else
reaches the rule, and unrouted product actions are still guarded by `policies.product.fallback`.
What a low clearance withholds is now said only where the service can act on it: in
`masked_filtered_fields` and `masked_filtered_attributes`. `required_clearance` has gone with the
floor it named.

| `details` field | `SECRET`+ (`ENV`) | `OFFICIAL-SENSITIVE` (`HEG`) | `OFFICIAL` (`BCC`) |
|---|---|---|---|
| `access_level` | `full` | `summary` | `basic` |
| `filter_contract` | `management-node.filter/1` | same | same |
| `allowed_filtered_fields` | 8 | 8 | 6 (no `source`, no `subscribedBy`) |
| `denied_filtered_fields` | `[]` | `[]` | `[]`, since a view sends no body and so asks for nothing |
| `masked_filtered_fields` | `[]` | `consumers` | `consumers`, `policyAttributes`, `source`, `subscribedBy` |
| `allowed_filtered_attributes` | 6 | 6 | 4 |
| `denied_filtered_attributes` | `[]` | `[]` | `[]` |
| `masked_filtered_attributes` | `[]` | `[]` | `[]` |
| `mask_sensitive_attributes` | `false` | `true` | `true` |
| `visible_fields` | all 15 | 14 | 11 |
| `text_search_fields` | `description`, `name` | same | same |
| `unmask_when` | `organisation.key eq ENV` | `eq HEG` | `eq BCC` |
| `row_filter` | discovery's, identically (Story 3) | same | same |
| `max_page_size` | 100 | 50 | 20 |
| `obligations` | `audit_access` | `aggregate_before_release`, `audit_access`, `mask_response` | `audit_access`, `mask_response` |

Three of those are discovery's business and a view simply carries them: `max_page_size` (one
product is one row), `text_search_fields` and the `denied_*` lists (a `GET` carries no criteria).
They are not special-cased away, because a document that dropped keys for one endpoint would be a
second document, and two documents are what drifted in the first place.

**`access_level` is reported, not a control.** What is actually withheld is
`masked_filtered_fields` and `masked_filtered_attributes`, which the service enforces by never
selecting those columns. Two mechanisms for "show less" is how they come apart, so nothing branches
on the level; it stays for the client and for the log. v1's `withheld_fields` is gone, because
`masked_filtered_fields` is the name Java already enforces, and the same name discovery uses.

**The decision is discovery's decision.** `view_test.rego` asserts, for every sample organisation
and for inputs with and without a body, that the two rules return the same `allow`, the same
`reasons` and details that are equal once `access_level` and `evaluation` are removed, and that
view's row filter admits exactly the products discovery's admits, for every sample product. That is
the build-time guarantee that "discoverable but not viewable" and "viewable but not discoverable"
are both impossible.

### V1: `GET /api/v1/product/3` (`FloodRiskMapZones`, offered by `ENV`)

| Org | HTTP | `allow` | `reasons` | `access_level` | `masked_filtered_fields` |
|---|---|---|---|---|---|
| `ENV` | 200 | `true` | `[]` | `full` | `[]` |
| `HEG` | 200 | `true` | `[]` | `summary` | `[consumers]` |
| `BCC` | 200 | `true` | `[]` | `basic` | `[consumers, policyAttributes, source, subscribedBy]` |

### V2: the same caller, three different products

`HEG` passes the gate once and then gets a different answer per product, decided by the row filter
in the query rather than by a second decision:

| Product | Why | HTTP |
|---|---|---|
| 3 `FloodRiskMapZones` (`ENV`) | non-personal, validated | 200, `consumers` withheld |
| 2 `PendingPlanningApplications` (`BCC`) | directly identifiable and risk-tagged; `HEG` is not cleared to `SECRET` | 404 |
| 12 `EmptyHomesRegister` (`HEG`) | superseded quality, but `HEG`'s own, via the row filter's first branch | 200, and `unmask_when` shows who is using it |

Exactly the products of Story 3's D1 table, because it is the same predicate: `ENV` may view 15 of
the 17, `HEG` 12 and `BCC` 8, including, for `BCC`, its own directly identifiable, risk-tagged
`PendingPlanningApplications`, which it offers.

`BCC`'s decision in full: the document discovery gives it, bar `access_level`:

```json
{
  "allow": true,
  "reasons": [],
  "policy": {"id": "product.view", "version": "policies.product.view/3.0.0", "resolution": "exact"},
  "details": {
    "access_level": "basic",
    "filter_contract": "management-node.filter/1",
    "allowed_filtered_fields": ["description", "name", "organisation.key", "organisation.name", "topic", "type"],
    "denied_filtered_fields": [],
    "masked_filtered_fields": ["consumers", "policyAttributes", "source", "subscribedBy"],
    "allowed_filtered_attributes": ["organisation.jurisdictions", "organisation.responsibility_areas",
                                    "quality_designation", "record_unit"],
    "denied_filtered_attributes": [],
    "masked_filtered_attributes": [],
    "mask_sensitive_attributes": true,
    "visible_fields": ["description", "name", "organisation", "organisation.key", "organisation.name",
                       "producer", "producer.active", "producer.description", "producer.name", "topic", "type"],
    "text_search_fields": ["description", "name"],
    "unmask_when": [{"names": ["subscribedBy", "consumers"],
                     "when": {"type": "comparison", "field": "organisation.key", "operator": "eq", "values": ["BCC"]}}],
    "row_filter": {"type": "group", "combinator": "or", "nodes": [
      {"type": "comparison", "field": "organisation.key", "operator": "eq", "values": ["BCC"]},
      {"type": "group", "combinator": "and", "nodes": [
        {"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised", "non_personal"]},
        {"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": ["validated"]},
        {"type": "comparison", "attribute": "population_risk_tags", "operator": "none_of",
         "values": ["children", "domestic_abuse_survivors", "protected_witnesses", "rare_condition_cohorts"]},
        {"type": "comparison", "attribute": "coverage_jurisdictions", "operator": "any_of", "values": ["Bristol", "England"]}
      ]}
    ]},
    "max_page_size": 20,
    "obligations": ["audit_access", "mask_response"]
  }
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

`POST /api/v1/product/discover` is answered by `product/discover.rego` (resolution `exact`),
version `policies.product.discover/3.2.0`.

> Discovery powers a product search. The rule does not return products; it returns the search
> contract for the caller: what they may search, sort and text-search on, what may be selected and
> what must be masked, which products may appear at all, how big a page is, and what the search
> layer must also do. The service asks **once per search** and turns that contract into one SQL
> query, so the contract is the query.

### What the caller sends

`filters` is a list of explicit comparisons, so a caller says whether it is filtering on a **field**
or an **attribute**, of which entity, and how. `sort`, `page` and `size` come with it:

```json
{
  "text": "flood",
  "filters": [
    {"field": "type", "operator": "eq", "values": ["topic"]},
    {"attribute": "record_unit", "operator": "in", "values": ["property", "geographic_area"]},
    {"scope": "organisation", "field": "key", "operator": "in", "values": ["ENV"]}
  ],
  "sort": [{"field": "name", "direction": "asc"}],
  "page": 0, "size": 20
}
```

The rule reads the body **as posted**, before the handler has validated it. A `filters` that is not
a list, or an entry naming no string field or attribute, asks for nothing: the handler answers `400`
for the shape, and the decision is never left undefined.

### Fields and attributes

A search layer handles the two differently, so the contract describes them separately:

| | Fields | Attributes |
|---|---|---|
| What they are | properties of the product and the entities around it, as the API names them | policy attributes stored in `policy_attribute_value` |
| Examples | `name`, `description`, `topic`, `type`, `source`, `organisation.key`, `subscribedBy`, `consumers` | `identifiability`, `quality_designation`, `organisation.jurisdictions` |
| How a search uses them | columns of the page query | an `EXISTS` over the attribute table |
| Who owns the list | closed, and owned by Java: `ProductField` and `ProductBlock`, mirrored in `lib/product.rego` | open, and owned by the data |

**Names are qualified.** A product's own field or attribute is written bare (`name`,
`identifiability`); anything else carries its entity as a prefix (`organisation.key`,
`organisation.jurisdictions`). A request writes the same thing as `{"scope": "organisation",
"field": "key"}`; the rule qualifies it before answering, so the contract's lists need only one
string per name.

Nothing is guessed from a name any more: the caller says `field` or `attribute`, and a name that is
neither known nor allowed is simply refused.

### The contract

| `details` field | Rule | `ENV` | `HEG` | `BCC` |
|---|---|---|---|---|
| `filter_contract` | the predicate grammar `row_filter` and `unmask_when` are written in | `management-node.filter/1` | same | same |
| `allowed_filtered_fields` | the filterable vocabulary minus what this caller may not see | `subscribedBy`, `description`, `name`, `organisation.key`, `organisation.name`, `source`, `topic`, `type` | the same eight | `description`, `name`, `organisation.key`, `organisation.name`, `topic`, `type` |
| `allowed_filtered_attributes` | `quality_designation`, `record_unit` and the organisation's descriptive attributes for everyone; + `temporal_resolution` for research; + `identifiability` at `OFFICIAL-SENSITIVE`; + `population_risk_tags` at `SECRET` | + `identifiability`, `population_risk_tags` | + `identifiability`, `temporal_resolution` | the four everyone gets |
| `denied_filtered_fields` / `_attributes` | names asked for, by a filter **or a sort**, that are not allowed | per request | per request | per request |
| `masked_filtered_fields` | by clearance: none at `SECRET`; `consumers` at `OFFICIAL-SENSITIVE`; also `subscribedBy`, `policyAttributes`, `source` below that | `[]` | `consumers` | `subscribedBy`, `consumers`, `policyAttributes`, `source` |
| `masked_filtered_attributes` | attributes this rule masks by name; none today | `[]` | `[]` | `[]` |
| `mask_sensitive_attributes` | whether attributes flagged `sensitive` in the catalogue are withheld: below `SECRET` | `false` | `true` | `true` |
| `visible_fields` | the whole vocabulary minus `masked_filtered_fields`; the `SELECT` is built from it | all 15 | 14 | 11 |
| `text_search_fields` | `name` and `description`, minus what is denied or masked | `description`, `name` | same | same |
| `unmask_when` | shows `subscribedBy` and `consumers` on the caller's **own** products, as a condition the database evaluates | `organisation.key eq ENV` | `eq HEG` | `eq BCC` |
| `row_filter` | which products may appear; see below | widest | middle | narrowest |
| `max_page_size` | 100 at `SECRET`, 50 at `OFFICIAL-SENSITIVE`, 20 below | 100 | 50 | 20 |
| `obligations` | `audit_access` always; `mask_response` when anything is withheld; `aggregate_before_release` for research | `audit_access` | `aggregate_before_release`, `audit_access`, `mask_response` | `audit_access`, `mask_response` |
| `evaluation` | `request`, or `candidate` when judging one product | | | |

Two things are deliberately **not** in the contract:

- **which attributes are sensitive.** That is data (`policy_attribute_definition.sensitive`), read
  by the service in every scope. The rule only says whether this caller has them withheld, so
  flagging a new attribute takes effect with no rule change. `population_risk_tags` and (in the
  sample data) the organisations' `authorised_classifications` and `permitted_purposes` are flagged.
- **`apply_boundary_filter`.** A local remit is now a condition on the data (below), not a duty the
  search layer is left to discharge, and an obligation the service cannot fulfil must refuse the
  search, so an unfulfillable one has no business being issued.

### Which products may be returned

`row_filter` is a predicate tree the database evaluates, and it always has the same two branches:

```
or( organisation.key eq <your key>            # your own products, always
  , and( identifiability in …                 # everything else has to qualify
       , quality_designation in …
       , population_risk_tags none_of …       # below SECRET
       , coverage_jurisdictions any_of … ) )  # local remit only
```

| Condition | `ENV` | `HEG` | `BCC` |
|---|---|---|---|
| your own products | always | always | always |
| `identifiability in`: by clearance, 1 → `anonymised`, `non_personal`; 2 → + `pseudonymised`; 3 → + `directly_identifiable` | all four | + `pseudonymised` | `anonymised`, `non_personal` |
| `quality_designation in`: `validated`; + `provisional` for a regulator; + `provisional`, `experimental` for research | `provisional`, `validated` | `experimental`, `provisional`, `validated` | `validated` |
| `population_risk_tags none_of` all risk tags, below `SECRET` | not applied | applied | applied |
| `coverage_jurisdictions any_of` your own jurisdictions, for a local remit | not applied | not applied | `Bristol`, `England` |

A product carrying none of an attribute a positive operator names is **not** matched, so a product
with no `coverage_jurisdictions` is outside every local remit and one with no `identifiability` is
seen by nobody but its owner. A refused search gets `row_filter: {"type": "literal", "value":
false}`: no rows, never all rows.

The rule also judges one product at a time (`input.resource.id` set). The service no longer asks it
to (the database applies `row_filter` instead), but `discover_test.rego` asserts that the two
**decide alike for every sample organisation and every sample product**, so a divergence between
the SQL path and the rule is caught at build time.

### Refusals

| Refused when | Reason |
|---|---|
| the token has no organisation | `organisation.missing` |
| no UK nation in `jurisdictions` | `organisation.jurisdiction_not_permitted` |
| no classification held | `organisation.clearance_missing` |
| a requested filter is a field you may not use | `filter.field_not_permitted:<name>` |
| a requested filter is an attribute you may not use | `filter.attribute_not_permitted:<name>` |
| a sort key is a name you may not use | `sort.not_permitted:<name>` |
| `text` is given and no column is left to search it against | `text.not_permitted` |

**A refusal names what was refused**, one reason per offending name, using the qualified name the
caller sent: `filter.attribute_not_permitted:organisation.authorised_classifications`. The code
before the colon stays the stable key for audit. It discloses nothing: the caller supplied the
name, and a name that does not exist is refused exactly like one that is forbidden.

Asking for something you may not use refuses the search rather than dropping the criterion.
Dropping it would return more than was asked for and hide the refusal. Free text counts as asking
to search `description`, so a caller who may not use that field is refused rather than quietly
searched on fewer columns.

Per candidate, the product is judged against the same conditions the row filter expresses, and
none of these fires for the caller's own products:

| Refused when | Reason |
|---|---|
| the product lacks `identifiability` or `quality_designation` | `product.attributes_missing` |
| its `identifiability` is not permitted | `product.identifiability_not_permitted` |
| its `quality_designation` is not permitted | `product.quality_not_permitted` |
| it carries a risk tag you may not see | `product.population_risk_not_permitted` |
| it covers nowhere in your remit, and your remit is local | `product.jurisdiction_not_permitted` |

### D1: `{"text": "land"}`, a plain search

All three are allowed. `ENV`'s full decision:

```json
{
  "allow": true,
  "reasons": [],
  "policy": {"id": "product.discover", "version": "policies.product.discover/3.2.0", "resolution": "exact"},
  "details": {
    "evaluation": "request",
    "filter_contract": "management-node.filter/1",
    "allowed_filtered_fields": ["subscribedBy", "description", "name", "organisation.key", "organisation.name", "source", "topic", "type"],
    "denied_filtered_fields": [],
    "masked_filtered_fields": [],
    "allowed_filtered_attributes": ["identifiability", "organisation.jurisdictions", "organisation.responsibility_areas", "population_risk_tags", "quality_designation", "record_unit"],
    "denied_filtered_attributes": [],
    "masked_filtered_attributes": [],
    "mask_sensitive_attributes": false,
    "visible_fields": ["subscribedBy", "consumers", "description", "name", "organisation", "organisation.key", "organisation.name", "policyAttributes", "producer", "producer.active", "producer.description", "producer.name", "source", "topic", "type"],
    "text_search_fields": ["description", "name"],
    "unmask_when": [{"names": ["subscribedBy", "consumers"], "when": {"type": "comparison", "field": "organisation.key", "operator": "eq", "values": ["ENV"]}}],
    "row_filter": {"type": "group", "combinator": "or", "nodes": [
      {"type": "comparison", "field": "organisation.key", "operator": "eq", "values": ["ENV"]},
      {"type": "group", "combinator": "and", "nodes": [
        {"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised", "directly_identifiable", "non_personal", "pseudonymised"]},
        {"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": ["provisional", "validated"]}
      ]}
    ]},
    "max_page_size": 100,
    "obligations": ["audit_access"]
  }
}
```

`HEG` and `BCC` differ exactly as the contract table says. `BCC`'s, abbreviated:

```json
"details": {
  "masked_filtered_fields": ["subscribedBy", "consumers", "policyAttributes", "source"],
  "mask_sensitive_attributes": true,
  "visible_fields": ["description", "name", "organisation", "organisation.key", "organisation.name", "producer", "producer.active", "producer.description", "producer.name", "topic", "type"],
  "unmask_when": [{"names": ["subscribedBy", "consumers"], "when": {"type": "comparison", "field": "organisation.key", "operator": "eq", "values": ["BCC"]}}],
  "row_filter": {"type": "group", "combinator": "or", "nodes": [
    {"type": "comparison", "field": "organisation.key", "operator": "eq", "values": ["BCC"]},
    {"type": "group", "combinator": "and", "nodes": [
      {"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised", "non_personal"]},
      {"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": ["validated"]},
      {"type": "comparison", "attribute": "population_risk_tags", "operator": "none_of", "values": ["children", "domestic_abuse_survivors", "protected_witnesses", "rare_condition_cohorts"]},
      {"type": "comparison", "attribute": "coverage_jurisdictions", "operator": "any_of", "values": ["Bristol", "England"]}
    ]}
  ]},
  "max_page_size": 20,
  "obligations": ["audit_access", "mask_response"]
}
```

**Products each contract admits.** This is what a search applying `row_filter` returns, and what
per-candidate evaluation returns; the two are asserted to agree. It is also the view table: the
same predicate decides `GET /api/v1/product/{id}`, so a ❌ here is a `404` there. `own` marks the offering
organisation, which always discovers its own product and sees who is using it:

| id | Product | Owner | `ENV` | `HEG` | `BCC` |
|---|---|---|---|---|---|
| 1 | `BrownfieldLandAvailability` | HEG | ✅ | ✅ own | ❌ `identifiability` |
| 2 | `PendingPlanningApplications` | BCC | ✅ | ❌ `identifiability`, `population_risk` | ✅ own |
| 3 | `FloodRiskMapZones` | ENV | ✅ own | ✅ | ✅ |
| 4 | `RiverLevelTelemetry` | ENV | ✅ own | ✅ | ✅ |
| 5 | `CoastalErosionForecast` | ENV | ✅ own | ✅ | ❌ `quality` |
| 6 | `PollutionIncidentReports` | ENV | ✅ own | ✅ | ❌ `identifiability`, `quality` |
| 7 | `WaterAbstractionLicences` | ENV | ✅ own | ❌ `identifiability` | ❌ `identifiability` |
| 8 | `UnclassifiedDraftDataset` | ENV | ✅ own | ❌ `attributes_missing` | ❌ `attributes_missing` |
| 9 | `AffordableHousingCompletions` | HEG | ✅ | ✅ own | ✅ |
| 10 | `HelpToBuyApplicants` | HEG | ✅ | ✅ own | ❌ `identifiability`, `population_risk` |
| 11 | `HousingNeedSurvey` | HEG | ❌ `quality` | ✅ own | ❌ `identifiability`, `quality` |
| 12 | `EmptyHomesRegister` | HEG | ❌ `quality` | ✅ own | ❌ `identifiability`, `quality` |
| 13 | `ScotlandLandRegisterExtract` | HEG | ✅ | ✅ own | ❌ `jurisdiction` |
| 14 | `BristolAirQualitySensors` | BCC | ✅ | ✅ | ✅ own |
| 15 | `BristolSchoolPlaces` | BCC | ✅ | ❌ `population_risk` | ✅ own |
| 16 | `BristolTemporaryAccommodation` | BCC | ✅ | ❌ `identifiability`, `population_risk` | ✅ own |
| 17 | `Bristol_Cycle%Counts` | BCC | ✅ | ✅ | ✅ own |

Reasons are abbreviated: each is `product.<name>_not_permitted`, except `product.attributes_missing`.
So `ENV` discovers 15 of the 17, `HEG` 12 and `BCC` 8, and each of the three sees every one of its
own, however sensitive: `BCC` discovers its directly identifiable, risk-tagged
`PendingPlanningApplications` because it offers it.

### D2 to D7: searching with filters, sorts and text

A refused search is HTTP `403`, with the refusal reasons in the response's `reasons`. The decision
in the log also shows the denied names and still lists what was allowed.

| Request | `ENV` | `HEG` | `BCC` |
|---|---|---|---|
| D2 `{"filters": [{"attribute": "identifiability", "values": ["pseudonymised"]}]}` | ✅ | ✅ | ❌ `filter.attribute_not_permitted:identifiability` |
| D3 `[{"attribute": "population_risk_tags", …}, {"attribute": "temporal_resolution", …}]` | ❌ `:temporal_resolution` | ❌ `:population_risk_tags` | ❌ both |
| D4 `[{"attribute": "population_risk_tags", "values": ["children"]}]` | ✅ | ❌ `:population_risk_tags` | ❌ `:population_risk_tags` |
| D5 `[{"attribute": "temporal_resolution", "values": ["annual"]}]` | ❌ `:temporal_resolution` | ✅ | ❌ `:temporal_resolution` |
| D6 `[{"field": "source", …}, {"field": "topic", …}]` | ✅ | ✅ | ❌ `filter.field_not_permitted:source` |
| D7 `{"sort": [{"field": "source", "direction": "desc"}]}` | ✅ | ✅ | ❌ `sort.not_permitted:source` |
| `{"filters": [{"scope": "organisation", "field": "key", "values": ["ENV"]}]}` | ✅ | ✅ | ✅, everyone may search by whose product it is |
| `{"filters": [{"field": "subscribedBy", "operator": "any_of", "values": ["BCC"]}]}` | ✅ | ✅ | ❌ `filter.field_not_permitted:subscribedBy` |
| `{"text": "flood"}` | ✅ name and description | ✅ | ✅ |

D3 refuses all three, each for a different gap: `ENV` is not a researcher, `HEG` is not cleared to
`SECRET`, and `BCC` is neither. D6 and D7 show the same rule applied to a filter and to a sort:
`BCC` may not see `source`, so it may neither filter nor order by it.

## Outcome matrix

| Call | `ENV` | `HEG` | `BCC` |
|---|---|---|---|
| V1 view product | ✅ full | ✅ summary | ✅ basic (the 8 products it discovers) |
| S1 subscribe, `cron` | ✅ accepted, 90 days | ✅ pending approval, 365 days | ❌ `schedule.type_not_permitted` |
| S2 subscribe, `interval` | ✅ accepted, 90 days | ✅ pending approval, 365 days | ✅ pending approval, 30 days |
| D1 discover | ✅ 8 fields + 6 attributes, page 100, 15 of 17 products | ✅ 8 fields + 6 attributes, page 50, 12 products, masked | ✅ 6 fields + 4 attributes, page 20, 8 products, masked |
| D2 filter `identifiability` | ✅ | ✅ | ❌ `filter.attribute_not_permitted:identifiability` |
| D3 filter risk tags + time | ❌ `:temporal_resolution` | ❌ `:population_risk_tags` | ❌ both |
| D4 filter risk tags | ✅ | ❌ `:population_risk_tags` | ❌ `:population_risk_tags` |
| D5 filter time resolution | ❌ `:temporal_resolution` | ✅ | ❌ `:temporal_resolution` |
| D6 filter `source`, `topic` | ✅ | ✅ | ❌ `filter.field_not_permitted:source` |
| D7 sort by `source` | ✅ | ✅ | ❌ `sort.not_permitted:source` |

Each organisation's refusals and limits trace back to its own attributes:

| Org | Refused | Limited |
|---|---|---|
| `ENV` | time-resolution search (D3, D5): not a researcher | 90-day subscriptions; no experimental products |
| `HEG` | risk-tag search (D3, D4): not cleared to `SECRET` | summary view; approval needed; no directly identifiable products other than its own; consumer details masked except on its own products |
| `BCC` | `cron` (S1): local remit; most searches | 30-day interval-only subscriptions needing approval; eight products visible to both discover and view, four of them its own; most data masked, and only what its own remit covers |

## What the rules can and cannot see

| Endpoint | Organisation attributes | Product attributes | Request body |
|---|---|---|---|
| view | yes | no (only the id, in `request.path`) | no |
| subscribe | yes | no | yes |
| discover, request level | yes | no | yes (`text`, `filters`, `sort`), as posted |
| discover, per candidate | yes | yes, plus the offering organisation (`resource.owner`) | as posted |

As a result:

- **Subscribe decides on who is asking, not which product.** Product 1 and product 3 get the same
  answer.
- **View and discover are one decision**: the whole contract, `row_filter` included, comes from
  `lib/product_access.rego`, and the two differ only in the key each adds (`access_level`,
  `evaluation`). Neither rule is shown the product: the condition goes into the query, and the
  database decides per row. View's decision is therefore the same for every id; what differs is
  whether the query returns anything (`200`) or not (`404`).
- **The rule is asked once per search.** What varies per product is decided by the database, from
  `row_filter` and `unmask_when`. The per-candidate level stays in the rule as the oracle the row
  filter is tested against, not as something the service calls.
- **A refused caller sees the reasons, not the details.** The `403` body is
  `{"status": 403, "message": "Access denied by policy", "reasons": [...], "errorId": "..."}`.
  `reasons` holds the codes in this document (e.g. `organisation.clearance_missing`); codes
  starting `dispatch.` or `policy.` are left out. The details (terms, search contract) and provenance
  appear only in the service log, when `application.opa.log-output` is on (it is in the `dev` profile).

## Where the rules live

| File | Contains |
|---|---|
| `policies/lib/entitlements.rego` | the four facts: `clearance`, `has_purpose`, `uk_jurisdiction`, `local_remit` |
| `policies/lib/product.rego` | the product `fields` and blocks a rule can name, `hidden_fields` by clearance and the `visible_fields` left over |
| `policies/lib/product_access.rego` | the whole product contract: the gates and their reasons, what may be filtered, sorted and text-searched, which products a caller may see (`row_filter`) and what is shown of each (`visible_fields`, the masking lists, `unmask_when`, `mask_sensitive_attributes`), the page size and the obligations. **It is** Story 1 and Story 3, which add one key each and nothing else |
| `policies/product/view.rego` | Story 1: the shared contract plus `access_level` |
| `policies/product/subscribe.rego` | Story 2 |
| `policies/product/discover.rego` | Story 3: the shared contract plus `evaluation`, and the per-candidate oracle the row filter is tested against |
| `policies/product/fallback.rego` | every other product action, such as `browse` (routed in `routing/data.json`): read-only for any caller with an organisation key, `details: {"access_level": "read"}`; these rules do not use the entitlement facts |
| `policies/lib/decision.rego` | `organisation_known` (the key is a non-empty string) and the deny-shaped starting document |
| `policies/lib/sample_data_test.rego` | the attributes above, exactly as stored, used by every test |
| `policies/lib/entitlements_test.rego`, `policies/product/*_test.rego` | tests that pin the per-organisation outcomes in this document |

On the Java side, `ProductDiscoveryPolicyDecisionDetails` reads the whole contract as typed
properties - the six lists as `fields()` and `attributes()`, and `rowFilter()`, `visibleFields()`,
`textSearchFields()`, `unmaskWhen()`, `maskSensitiveAttributes()`, `maxPageSize()` and
`obligations()` alongside them. `ProductViewPolicyDecisionDetails` carries the same contract fields
(minus the search-only ones) plus `accessLevel()`, so one reader and one masking implementation
serve both endpoints - the Java counterpart of `lib/product_access.rego`.
`ProductSearchContract` then turns it into what one search may do,
and `SqlPredicateCompiler` turns the row filter into the query's `WHERE` clause. A name the rule
uses must be one `ProductField` or `ProductBlock` knows, and an operator must be one
`ComparisonOperator` knows: anything else fails to bind, which is read as unreadable details and
therefore DENY.

## Trying it

Run the tests from `docker/opa`:

```bash
P="$PWD/policies:/p:ro"
docker run --rm -v "$P" openpolicyagent/opa:1.20.2 test --timeout 30s /p
```

The cross-checking tests (discovery's row filter against its own per-candidate verdict, and
view's decision and row filter against discovery's) evaluate a decision per organisation per
product, so they need more than OPA's 5s default per test.

Ask the running OPA directly. This is `BCC` searching on `identifiability` (D2); it is refused:

```bash
curl -s localhost:8181/v1/data/dispatch/decision -H 'content-type: application/json' -d '{
  "input": {
    "subject": {"organisation": {"key": "BCC", "attributes": {
      "authorised_classifications": ["OFFICIAL"], "permitted_purposes": ["service_delivery"],
      "jurisdictions": ["Bristol", "England"]}}},
    "action": "discover", "resource": {"kind": "product"},
    "request": {"method": "POST", "body": {"filters": [
      {"attribute": "identifiability", "operator": "eq", "values": ["pseudonymised"]}]}}
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
     three rules: subscribe (`organisation.purpose_not_permitted`), and discover and view alike
     (`organisation.clearance_missing`, `organisation.jurisdiction_not_permitted`).
2. **Keycloak's token endpoint needs a valid client certificate.** The ones under `docker/` and
   `docker/cert/` expired in July 2026.
3. **Run the service with the `dev` profile.** It turns on `application.opa.enabled` and
   `log-output`, and uses the `management-node` schema.
4. **Restart OPA after changing a rule:** `docker compose restart opa` in `docker/opa`.
5. **The caller holds `product_view`, `product_subscribe` and `product_discovery`.** Without the
   role, the call is refused before policy runs.
