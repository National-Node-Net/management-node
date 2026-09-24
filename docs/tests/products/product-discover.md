# Product Discover: Test Requirement Specification

**Repository:** `management-node`
**Endpoint:** `POST /api/v1/product/discover`
**Role required:** `product_discovery`
**Policy rule:** `policies.product.discover` (`docker/opa/policies/product/discover.rego`)
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`

---

A formal test specification for a manual testing team. It states what the API does, what decides
its behaviour, then gives numbered test cases (**Objective / Preconditions / Test data /
Given-When-Then**), and finally the edge cases.

Related documents:

- [`docs/DISCOVERY_TEST_SCENARIOS.md`](../../DISCOVERY_TEST_SCENARIOS.md): the guided tour of both
  product endpoints, the environment setup (§2), tokens (§3) and the sample catalogue (§4). **This
  document does not repeat the setup; do that first.**
- [`docs/POLICY_ENFORCEMENT.md`](../../POLICY_ENFORCEMENT.md): how a decision is reached, what a
  decision document contains, and the master switch.
- [`product-view.md`](product-view.md): the sibling specification for `GET /api/v1/product/{productId}`.

> ## Product ids differ between environments
>
> Product ids are database sequence values. `AffordableHousingCompletions` may be id 9 in one
> environment and 27 in another, and a re-run of the Flyway samples against a fresh database will
> not reproduce the ids you saw yesterday.
>
> **No expected result in this document is written in terms of an id.** Identify a product by its
> `name`. Where a test needs an id (to pass to the view or subscribe endpoint), take it from a
> discover response in the same session:
>
> ```bash
> id=$(discover "$ENV_TOKEN" '{"text":"FloodRiskMapZones"}' | jq -r '.products[0].id')
> ```
>
> A test that hardcodes an id will pass in one environment and fail in the next, and the failure
> will look like a policy defect. It is not one.

## Contents

- [1. What the API does](#1-what-the-api-does)
- [2. What governs it](#2-what-governs-it)
- [3. The request](#3-the-request)
- [4. The response](#4-the-response)
- [5. Critical fields for filtering](#5-critical-fields-for-filtering)
- [6. Test data](#6-test-data)
- [7. Shared preconditions](#7-shared-preconditions)
- [8. Test cases](#8-test-cases)
  - [A. Baseline and response shape (TC-01 – TC-04)](#a-baseline-and-response-shape)
  - [B. Free-text search (TC-05 – TC-07)](#b-free-text-search)
  - [C. Filtering on product fields (TC-08 – TC-15)](#c-filtering-on-product-fields)
  - [D. Filtering on policy attributes (TC-16 – TC-22)](#d-filtering-on-policy-attributes)
  - [E. Sorting (TC-23 – TC-27)](#e-sorting)
  - [F. Paging (TC-28 – TC-31)](#f-paging)
  - [G. Refusals by policy, 403 (TC-32 – TC-39)](#g-refusals-by-policy--403)
  - [H. Malformed requests, 400 (TC-40 – TC-42)](#h-malformed-requests--400)
  - [I. Policy switched off (TC-43 – TC-44)](#i-policy-switched-off)
- [9. Edge cases](#9-edge-cases)
- [10. Defect triage](#10-defect-triage)

---

## 1. What the API does

`POST /api/v1/product/discover` searches the catalogue of data products and returns one page of
**summaries** of the products the calling organisation is allowed to find.

It answers three questions at once, and keeping them apart explains nearly every surprise:

| Question | Decided by | What you see when the answer is "no" |
|---|---|---|
| May this caller search at all? | the `product_discovery` role, then the policy's caller gates | `403` |
| **Which products exist for this caller?** | the policy's `row_filter`, applied as the query's `WHERE` clause | the product is simply not in `products`, and not counted in `totalElements` |
| What may be asked, and what is shown? | the policy's allowed / masked lists | `403` naming the refused criterion, or a member absent from every result |

The second is the one that trips people up: **a product a caller may not discover is
indistinguishable from a product that does not exist.** It is not returned, not counted, and
filtering for it by name returns an empty page rather than a refusal. That is deliberate, since a count
is a disclosure.

Nothing in the Java code decides who sees what. The policy returns a *search contract*, and the
service compiles that contract into a single SQL statement. So a difference in results is one of
three things: a different calling organisation, different product data, or a bug.

## 2. What governs it

Four gates run in order. A request refused at any gate never reaches the next one.

| # | Gate | Refusal |
|---|---|---|
| 1 | Authentication: a valid bearer token | `401` |
| 2 | Authorisation: the `product_discovery` role in `resource_access["management-node"].roles` | `403 "Access denied: insufficient permissions for this operation"` |
| 3 | Request binding and bean validation (`@Valid` on the body) | `400` |
| 4 | Policy: the `product.discover` rule, evaluated by OPA | `403 "Access denied by policy"` with `reasons` |
| 5 | Criteria validation inside the handler | `400 "Invalid search criteria: …"` |

> **Ordering matters when you write a negative test.** Gate 3 (body shape) runs when the handler's
> arguments are resolved, which is *before* the enforcement point; gate 5 runs *inside* the
> handler, which is *after* it. So a malformed body is always a `400`, but a semantically invalid
> filter naming something the caller may not use is a **`403`**, not a `400`, because the policy judges
> the raw body first. See [TC-42](#tc-42--criteria-400s-decided-after-policy).

The calling **organisation comes from the token**, never from the request body. It is the
`organisation` claim, matched against `organisation.organisation_key` in the database. Three
organisations ship with the sample data: `ENV`, `HEG`, `BCC`.

OPA **fails closed**: if the PDP is down, unreachable or slow, every request is `403`. That is
correct behaviour, not a defect.

## 3. The request

Every member is optional. An empty body (`{}`, or no body at all) is valid and returns the first
page of everything the caller may discover.

```json
{
  "text": "flood",
  "filters": [
    { "scope": "product", "field": "type", "operator": "eq", "values": ["file"] },
    { "scope": "organisation", "attribute": "responsibility_areas", "operator": "any_of", "values": ["housing_delivery"] }
  ],
  "sort": [ { "field": "name", "direction": "desc" } ],
  "page": 0,
  "size": 20
}
```

| Member | Type | Constraint (`400` when broken) | Default |
|---|---|---|---|
| `text` | string | at most **255** characters | none, meaning no text condition |
| `filters` | array | at most **50** entries | `[]` |
| `filters[].scope` | `product` \| `organisation` | must be one of those two | `product` |
| `filters[].field` | string | at most 150 chars; **exactly one** of `field` / `attribute` | n/a |
| `filters[].attribute` | string | at most 150 chars; **exactly one** of `field` / `attribute` | n/a |
| `filters[].operator` | enum (below) | must be a known operator | chosen; see [default operator rules](#default-operator-rules) |
| `filters[].values` | array of string / number / boolean | at most **50** values, each at most 255 characters | `[]` |
| `sort` | array | at most **3** entries | sort by `name` ascending |
| `sort[].field` / `.attribute` / `.scope` | as above | exactly one of `field` / `attribute` | n/a |
| `sort[].direction` | `asc` \| `desc` (case-insensitive) | must match `asc\|desc` | `asc` |
| `page` | integer | `>= 0` | `0` |
| `size` | integer | `>= 1`; clamped down to the policy's `maxPageSize` | `20` |

### Operators

| Operator | Values | Applies to | Meaning |
|---|---|---|---|
| `eq` | exactly 1 | field, attribute | equal, case-insensitive |
| `neq` | exactly 1 | field, attribute | not equal; **also matches products where the value is absent or NULL** |
| `in` | any number | field, attribute | equal to any of |
| `not_in` | any number | field, attribute | equal to none of; also matches absent/NULL |
| `any_of` | any number | field, attribute | same as `in`; reads better on a multi-valued attribute |
| `all_of` | any number | **attribute only** | carries every one of these values (`400` on a field) |
| `none_of` | any number | field, attribute | carries none of these; also matches absent/NULL |
| `contains` | exactly 1 | field, attribute | case-insensitive substring |
| `lt` `lte` `gt` `gte` | exactly 1, **numeric** | **attribute only** | numeric comparison (`400` on a field, `400` on a non-numeric value) |
| `exists` | **0** | field, attribute | the value is present / not NULL |
| `not_exists` | **0** | field, attribute | the value is absent / NULL |

The operators marked "also matches absent" are the **negative** operators (`neq`, `not_in`,
`none_of`, `not_exists`). Every other operator requires the value to be present, so a missing
attribute never widens a result.

### Default operator rules

When `operator` is omitted it is chosen from the target and the number of values:

| Situation | Operator chosen |
|---|---|
| exactly one value, on a **text-searchable field** (`name`, `description`) | `contains` |
| exactly one value, on anything else (`topic`, `type`, `source`, `organisation.key`, `organisation.name`, `subscribedBy`, **any attribute**) | `eq` |
| zero values, or more than one value | `in` |

All comparisons are **case-insensitive**, whichever operator is used.

This is the single most common source of "my filter returned nothing". `organisation.name` is not
text-searchable, so a single value defaults to `eq` and must match the whole name.

```jsonc
// eq, matches nothing: the organisation is called "Bristol City Council (BCC)"
{"filters": [{"field": "organisation.name", "values": ["Bristol"]}]}

// contains, matches every product Bristol City Council offers
{"filters": [{"field": "organisation.name", "operator": "contains", "values": ["Bristol"]}]}

// name IS text-searchable, so this one is already a substring match
{"filters": [{"field": "name", "values": ["bristol"]}]}
```

## 4. The response

```json
{
  "products": [
    {
      "id": 12,
      "name": "FloodRiskMapZones",
      "description": "Flood risk zones for England and Wales as geographic areas, …",
      "type": "topic",
      "organisation": { "key": "ENV", "name": "Environment Agency (ENV)" }
    }
  ],
  "page": { "number": 0, "size": 20, "numberOfElements": 1, "totalElements": 1, "totalPages": 1 },
  "policy": {
    "filterableFields": ["description", "name", "organisation.key", "organisation.name", "source", "subscribedBy", "topic", "type"],
    "filterableAttributes": ["identifiability", "organisation.jurisdictions", "organisation.responsibility_areas", "population_risk_tags", "quality_designation", "record_unit"],
    "textSearchFields": ["name", "description"],
    "maskedFields": [],
    "maskedAttributes": [],
    "maxPageSize": 100,
    "obligations": ["audit_access"]
  }
}
```

### A result is a summary

A discovery result carries **only**: `id`, `name`, `description`, `type`, and an `organisation`
carrying `key` and `name`. Nothing else: no `topic`, no `source`, no `producer`, no `consumers`,
no `subscribedBy`, no attribute maps. Those belong to
[`GET /api/v1/product/{productId}`](product-view.md).

**Filtering and sorting are not limited to those members.** A caller may filter and sort on
anything in its allowed lists, including `topic` and `source`, which no result carries. This
separation is deliberate and has its own test case ([TC-09](#tc-09--filter-on-a-field-the-result-does-not-carry)).

Absent members are **omitted**, never `null` and never `""`. A product with no description simply
has no `description` key.

### The `page` object

| Field | Means |
|---|---|
| `number` | which page this is, from 0 |
| `size` | the page size **applied**, after clamping to `maxPageSize` |
| `numberOfElements` | how many products **this response carries** |
| `totalElements` | how many products match in all, **for this caller** |
| `totalPages` | how many pages of `size` those fill |

`size` and `numberOfElements` differ on the last page and whenever fewer products match than a page
holds. `.products | length` must always equal `.page.numberOfElements`.

### The `policy` block

Present only when policy enforcement is on. It is the contract the caller was given: a UI builds
its filter panel from it, and it is the fastest way to explain any refusal in this document.

**`policy` absent means policy enforcement is switched off**, not that the caller has no
restrictions. See [section I](#i-policy-switched-off).

## 5. Critical fields for filtering

These are the levers. Everything in section 8 is a permutation of them.

### 5.1 Product fields

`ProductField` is the registry; a name outside it cannot be filtered, sorted or selected.

| API name | Filterable | Sortable | Text-searched | In the summary |
|---|---|---|---|---|
| `id` | yes (service-level), but **no policy permits it**, so `403` while policy is on | no | no | yes, always |
| `name` | yes | yes | **yes** | yes |
| `description` | yes | **no** | **yes** | yes |
| `topic` | yes | yes | no | no |
| `type` | yes | yes | no | yes |
| `source` | yes | yes | no | no |
| `organisation.key` | yes | yes | no | yes |
| `organisation.name` | yes | yes | no | yes |
| `subscribedBy` | yes | **no** | no | no |
| `producer.name`, `producer.description`, `producer.active` | no | no | no | no |

Two rows above are traps worth their own cases: `description` is filterable but **not sortable**
(sorting on it is a `400`, [TC-26](#tc-26--sort-on-a-filterable-but-unsortable-field)), and
`subscribedBy` is filterable but not sortable for the same reason.

### 5.2 Policy attributes

Attributes are data, not code: they live in `policy_attribute_value` and are read through the
`policy_attribute_live_value` view. A new attribute becomes filterable with no code change; the
attribute *name* is a bound SQL parameter.

| Attribute | Scope | Values in the sample data | Multi-valued | Sensitive |
|---|---|---|---|---|
| `record_unit` | product | `person`, `household`, `business`, `property`, `geographic_area` | no | no |
| `identifiability` | product | `non_personal`, `anonymised`, `pseudonymised`, `directly_identifiable` | no | no |
| `quality_designation` | product | `validated`, `provisional`, `experimental`, `superseded` | no | no |
| `temporal_resolution` | product | `event_level`, `hourly`, `daily`, `monthly`, `annual` | no | no |
| `population_risk_tags` | product | `children`, `protected_witnesses`, `domestic_abuse_survivors`, `rare_condition_cohorts` | **yes** | **yes** |
| `coverage_jurisdictions` | product | `England`, `Wales`, `Scotland`, `Bristol` | **yes** | no |
| `jurisdictions` | organisation | `England`, `Wales`, `Scotland`, `Bristol` | **yes** | no |
| `responsibility_areas` | organisation | `environmental_protection`, `flood_risk_management`, `housing_delivery`, `land_availability`, `urban_planning`, `public_health` | **yes** | no |
| `authorised_classifications` | organisation | `OFFICIAL`, `OFFICIAL-SENSITIVE`, `SECRET` | **yes** | **yes** |
| `permitted_purposes` | organisation | `service_delivery`, `regulatory_oversight`, `statistical_analysis` | **yes** | **yes** |

`coverage_jurisdictions`, `authorised_classifications` and `permitted_purposes` are used *by* the
policy but are **not** in anyone's allowed list, so filtering on them is a `403`.

### 5.3 Naming an organisation-scoped field or attribute

Two spellings exist, and they mean the same thing, for fields and for attributes alike:

```jsonc
{"scope": "organisation", "field": "key",  "values": ["BCC"]}   // the scope form
{"field": "organisation.key",              "values": ["BCC"]}   // the qualified form
```

| Target | `scope` form | Qualified form |
|---|---|---|
| **field** (`organisation.key`, `organisation.name`) | works | works |
| **attribute** (`organisation.responsibility_areas`) | works | works |

A qualified name the caller did not scope carries its own scope, which is how the policy's own
lists are written, so the two forms are also refused identically; a refusal names whichever
spelling the caller sent. Both are asserted by
[TC-20](#tc-20--organisation-scoped-attribute-filter-and-the-two-spellings).

> **If you are working from an older test plan:** the qualified spelling of an *attribute* used to
> pass the permission check and then search the product scope, returning `200` with 0 results. That
> is fixed; a test expecting an empty page there is now the thing that is wrong.

### 5.4 What each organisation may filter on

Measured against the running policy. This table is the oracle for every `403` in section G.

| | **ENV** | **HEG** | **BCC** |
|---|---|---|---|
| clearance | SECRET | OFFICIAL-SENSITIVE | OFFICIAL |
| purposes | `service_delivery`, `regulatory_oversight` | `service_delivery`, `statistical_analysis` | `service_delivery` |
| jurisdictions | England, Wales | England, Scotland | England, **Bristol** (local remit) |
| **allowed filter fields** | `description`, `name`, `organisation.key`, `organisation.name`, `source`, `subscribedBy`, `topic`, `type` | same as ENV | `description`, `name`, `organisation.key`, `organisation.name`, `topic`, `type` |
| **allowed filter attributes** | `identifiability`, `organisation.jurisdictions`, `organisation.responsibility_areas`, `population_risk_tags`, `quality_designation`, `record_unit` | `identifiability`, `organisation.jurisdictions`, `organisation.responsibility_areas`, `quality_designation`, `record_unit`, `temporal_resolution` | `organisation.jurisdictions`, `organisation.responsibility_areas`, `quality_designation`, `record_unit` |
| **masked fields** | *(none)* | `consumers` | `consumers`, `policyAttributes`, `source`, `subscribedBy` |
| `textSearchFields` | `name`, `description` | `name`, `description` | `name`, `description` |
| `maxPageSize` | 100 | 50 | 20 |
| `obligations` | `audit_access` | `aggregate_before_release`, `audit_access`, `mask_response` | `audit_access`, `mask_response` |

Three differences in that table are worth a test case each, because they show *why* a filter is
allowed rather than merely *that* it is:

| Filter | Allowed for | Because |
|---|---|---|
| `population_risk_tags` | **ENV only** | it needs `SECRET` clearance |
| `temporal_resolution` | **HEG only** | it needs the `statistical_analysis` purpose |
| `source`, `subscribedBy` | **ENV and HEG, not BCC** | they are *masked* for BCC, and masked ⇒ not filterable (`allowed − masked`) |

The last rule is the important one: **anything a caller cannot see, it cannot filter on**, since otherwise a
filter would be a way to read it one yes/no answer at a time.

## 6. Test data

The catalogue is 17 products across three organisations, loaded by Flyway from
`src/main/resources/db/samples/`. Reproduced from
[`DISCOVERY_TEST_SCENARIOS.md` §4](../../DISCOVERY_TEST_SCENARIOS.md#4-the-sample-catalogue).

| Product | Owner | type | source | identifiability | quality | risk tags | record unit | temporal | coverage |
|---|---|---|---|---|---|---|---|---|---|
| FloodRiskMapZones | ENV | topic | n/a | non_personal | validated | n/a | geographic_area | daily | England, Wales |
| RiverLevelTelemetry | ENV | topic | `kafka://env/river-levels` | non_personal | validated | n/a | geographic_area | hourly | England, Wales |
| CoastalErosionForecast | ENV | file | `s3://env/coastal-erosion` | non_personal | experimental | n/a | geographic_area | annual | England |
| PollutionIncidentReports | ENV | topic | `kafka://env/pollution` | pseudonymised | provisional | n/a | business | event_level | England, Wales |
| WaterAbstractionLicences | ENV | file | `s3://env/abstraction` | directly_identifiable | validated | n/a | business | monthly | England |
| UnclassifiedDraftDataset | ENV | file | n/a | **n/a** | **n/a** | n/a | geographic_area | n/a | England |
| BrownfieldLandAvailability | HEG | topic | n/a | pseudonymised | validated | n/a | property | annual | England |
| AffordableHousingCompletions | HEG | file | `s3://heg/completions` | anonymised | validated | n/a | property | annual | England |
| HelpToBuyApplicants | HEG | topic | `kafka://heg/help-to-buy` | directly_identifiable | validated | children | person | event_level | England |
| HousingNeedSurvey | HEG | file | `s3://heg/need-survey` | pseudonymised | experimental | n/a | household | annual | England, Scotland |
| EmptyHomesRegister | HEG | file | `s3://heg/empty-homes` | pseudonymised | **superseded** | n/a | property | monthly | England |
| ScotlandLandRegisterExtract | HEG | file | `s3://heg/scotland-land` | anonymised | validated | n/a | property | annual | **Scotland only** |
| PendingPlanningApplications | BCC | topic | n/a | directly_identifiable | provisional | domestic_abuse_survivors, protected_witnesses | household | event_level | Bristol |
| BristolAirQualitySensors | BCC | topic | `kafka://bcc/air-quality` | non_personal | validated | n/a | geographic_area | hourly | Bristol |
| BristolSchoolPlaces | BCC | file | `s3://bcc/school-places` | anonymised | provisional | children | person | annual | Bristol |
| BristolTemporaryAccommodation | BCC | topic | `kafka://bcc/temp-accommodation` | directly_identifiable | validated | domestic_abuse_survivors | household | event_level | Bristol |
| `Bristol_Cycle%Counts` | BCC | **n/a** | n/a | non_personal | validated | n/a | geographic_area | daily | Bristol |

Organisation names are `Environment Agency (ENV)`, `Homes England (HEG)`,
`Bristol City Council (BCC)`.

### 6.1 Who discovers what (empty body)

| Caller | `totalElements` | The set |
|---|---|---|
| **ENV** | **15** | everything **except** HousingNeedSurvey and EmptyHomesRegister |
| **HEG** | **12** | its own 6, plus FloodRiskMapZones, RiverLevelTelemetry, CoastalErosionForecast, PollutionIncidentReports, BristolAirQualitySensors, `Bristol_Cycle%Counts` |
| **BCC** | **8** | its own 5, plus FloodRiskMapZones, RiverLevelTelemetry, AffordableHousingCompletions |

Every expected count in section 8 is an intersection of a filter with one of these three sets.
When a count differs, re-derive it from this table before reporting a defect.

### 6.2 Grants (for `subscribedBy`)

| Product | Organisations holding a grant |
|---|---|
| FloodRiskMapZones | BCC |
| PendingPlanningApplications | HEG |
| BrownfieldLandAvailability | ENV |
| RiverLevelTelemetry | BCC (×2), HEG |
| PollutionIncidentReports | BCC |
| AffordableHousingCompletions | BCC, ENV |
| HelpToBuyApplicants | ENV |
| BristolAirQualitySensors | ENV (×2), HEG |
| BristolTemporaryAccommodation | HEG |

The other 8 products have no grant at all.

## 7. Shared preconditions

Referenced by number from each test case.

| # | Precondition |
|---|---|
| **P1** | Keycloak, PostgreSQL and OPA are running (`docker compose` stacks under `docker/`); `curl -s http://localhost:8181/health` returns healthy. |
| **P2** | The application is running over mTLS on `https://localhost:8090` with the sample data loaded (`SELECT count(*) FROM mn.product` = 17). |
| **P3** | `application.opa.enabled=true`. Confirm by the **presence** of the `policy` block in any response; the startup `WARN` about OPA being switched off must *not* be in the log. |
| **P4** | Three tokens are held, each with the `product_discovery` role and its own `organisation` claim: `$ENV_TOKEN` (`ENV`), `$HEG_TOKEN` (`HEG`), `$BCC_TOKEN` (`BCC`). Decode each and check the claim before starting; one stale token sent three times is the most common cause of a false failure. |
| **P5** | `application.opa.enabled=false` and the service restarted (section I only). |

Setup instructions for P1–P4 are in
[`DISCOVERY_TEST_SCENARIOS.md` §2 and §3](../../DISCOVERY_TEST_SCENARIOS.md#2-setting-up).

Helper used throughout:

```bash
discover() {   # discover <TOKEN> '<json body>'
  curl -sk --cert client.crt --key client.key \
    -X POST 'https://localhost:8090/api/v1/product/discover' \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "${2:-\{\}}"
}
names() { jq -r '[.products[].name] | sort | join(", ")'; }
```

---

## 8. Test cases

### A. Baseline and response shape

#### TC-01: Empty body returns the caller's whole catalogue

**Objective.** Confirm that a search with no criteria returns exactly the products the calling
organisation may discover, and that the three organisations differ.

**Preconditions.** P1–P4.

**Test data.** `{}` sent as each of the three tokens.

**Given** the sample catalogue of 17 products
**When** an empty body is posted as ENV, then as HEG, then as BCC
**Then** each response is `200`, and:

| Caller | `page.totalElements` | `page.numberOfElements` | `page.size` | `page.totalPages` |
|---|---|---|---|---|
| ENV | 15 | 15 | 20 | 1 |
| HEG | 12 | 12 | 20 | 1 |
| BCC | 8 | 8 | 20 | 1 |

**And** the product names match §6.1 exactly.

```bash
for t in "$ENV_TOKEN" "$HEG_TOKEN" "$BCC_TOKEN"; do discover "$t" '{}' | jq '.page.totalElements'; done
```

*Why:* the row filter is the policy's, not the request's. Identical counts for all three callers
mean either the same identity was sent three times (P4) or policy is not shaping the query at all.

---

#### TC-02: A result is a summary and nothing more

**Objective.** Confirm the response carries exactly the summary members, and that absent members
are omitted rather than null.

**Preconditions.** P1–P4.

**Test data.** `{"text": "FloodRiskMapZones"}` as ENV, the most privileged caller, so nothing is
absent because of masking.

**Given** ENV, which has no masked fields at all
**When** the request is posted
**Then** the single product carries exactly `id`, `name`, `description`, `type`, `organisation`
**And** `organisation` carries exactly `key` and `name`
**And** `topic`, `source`, `producer`, `consumers`, `subscribedBy`, `attributes` are **absent keys**
They are not present with a `null` value.

```bash
discover "$ENV_TOKEN" '{"text":"FloodRiskMapZones"}' | jq '.products[0] | keys, (.organisation | keys)'
# ["description","id","name","organisation","type"]
# ["key","name"]
```

*Why:* the summary is enforced by not selecting the other columns at all. If `source` appears here
for any caller, the projection is not being applied to the query.

---

#### TC-03: The `policy` block reports the caller's contract

**Objective.** Confirm the response reports what the caller was allowed, and that it matches §5.4.

**Preconditions.** P1–P4.

**Test data.** `{}` as each token; inspect `.policy`.

**Given** three organisations with different clearances
**When** `.policy` is read from each response
**Then** it matches §5.4 exactly, in particular:

```bash
discover "$BCC_TOKEN" '{}' | jq .policy
```

```json
{
  "filterableFields": ["description", "name", "organisation.key", "organisation.name", "topic", "type"],
  "filterableAttributes": ["organisation.jurisdictions", "organisation.responsibility_areas", "quality_designation", "record_unit"],
  "textSearchFields": ["name", "description"],
  "maskedFields": ["consumers", "policyAttributes", "source", "subscribedBy"],
  "maskedAttributes": [],
  "maxPageSize": 20,
  "obligations": ["audit_access", "mask_response"]
}
```

**And** HEG shows `maxPageSize: 50`, `maskedFields: ["consumers"]` and obligations
`["aggregate_before_release", "audit_access", "mask_response"]`
**And** ENV shows `maxPageSize: 100`, `maskedFields: []` and obligations `["audit_access"]`.

*Why:* every `403` in section G is predictable from this block. A tester who reads it first will
not raise a defect for a correct refusal. Note `maskedFields` and `filterableFields` never overlap
Masked implies not filterable.

---

#### TC-04: The identity decides the catalogue, not the request

**Objective.** Confirm the organisation is taken from the token.

**Preconditions.** P1–P4.

**Test data.** The *identical* body `{"filters":[{"field":"organisation.key","values":["BCC"]}]}`
sent as ENV and as HEG.

**Given** one request body and two different tokens
**When** both are posted
**Then** ENV gets **5** products (PendingPlanningApplications, BristolAirQualitySensors,
BristolSchoolPlaces, BristolTemporaryAccommodation, `Bristol_Cycle%Counts`)
**And** HEG gets **2** (BristolAirQualitySensors, `Bristol_Cycle%Counts`)
**And** neither response contains any product the caller could not discover with an empty body.

*Why:* a caller's filter can only narrow what policy already permits. HEG asking about BCC's
products still cannot reach the three that are directly identifiable or risk-tagged.

---

### B. Free-text search

#### TC-05: Free text matches `name` **or** `description`

**Objective.** Confirm the text term runs against both text-searchable columns, case-insensitively,
as a substring.

**Preconditions.** P1–P4.

**Test data.**

| Body | Caller | Expected names |
|---|---|---|
| `{"text": "flood"}` | ENV | FloodRiskMapZones *(name and description)*, RiverLevelTelemetry *(description: "Used for flood warning")* |
| `{"text": "FLOOD"}` | ENV | the same two, since matching is case-insensitive |
| `{"text": "housing"}` | ENV | AffordableHousingCompletions *(name)*, BrownfieldLandAvailability *(description)* |
| `{"text": "housing"}` | HEG | those two **plus** HousingNeedSurvey |
| `{"text": "Scotland"}` | HEG | ScotlandLandRegisterExtract *(name)*, HousingNeedSurvey *(description)* |
| `{"text": "Scotland"}` | BCC | **0 results** |

**Given** the catalogue
**When** each body is posted
**Then** the names match, `totalElements` equals the number of names, and the response is `200` in
every case including the empty one.

*Why:* `housing` as ENV vs HEG shows text searching and the row filter composing: HousingNeedSurvey
is experimental, which HEG's `statistical_analysis` purpose earns and ENV's purposes do not. `Scotland`
as BCC shows the jurisdiction condition: BCC has a local remit, and the product covers Scotland only.

---

#### TC-06: Free text never matches `topic` or `source`

**Objective.** Confirm only `name` and `description` are text-searched, and that the same strings
are still reachable through an explicit filter.

**Preconditions.** P1–P4.

**Test data.**

| Body | Caller | Expected |
|---|---|---|
| `{"text": "kafka"}` | ENV | **0 results**, although `kafka://` appears in five `source` values |
| `{"text": "topic."}` | ENV | **0 results**, although every product's `topic` begins `topic.` |
| `{"filters":[{"field":"source","operator":"contains","values":["kafka://"]}]}` | ENV | **5**: RiverLevelTelemetry, PollutionIncidentReports, HelpToBuyApplicants, BristolAirQualitySensors, BristolTemporaryAccommodation |
| `{"filters":[{"field":"topic","operator":"contains","values":["topic."]}]}` | ENV | **15**, all of ENV's catalogue |

**Given** the catalogue
**When** each is posted
**Then** the counts match.

*Why:* `topic` and `source` are identifiers, not prose, so they are deliberately excluded from free
text. They remain available as explicit filters, which is the distinction the next case tests.

---

#### TC-07: A text term is a value, never a pattern

**Objective.** Confirm the term is bound as a parameter and its LIKE metacharacters are escaped.

**Preconditions.** P1–P4.

**Test data.**

| Body | Caller | Expected |
|---|---|---|
| `{"text": "Bristol_Cycle%Counts"}` | BCC | exactly **1**, `Bristol_Cycle%Counts` |
| `{"text": "%"}` | BCC | **0 results** |
| `{"text": "_"}` | BCC | **1**, only the product whose name literally contains `_` |
| `{"text": "' OR 1=1 --"}` | ENV | **0 results**, `200`, no error |
| `{"text": "   "}` | ENV | **15**, since whitespace-only text is trimmed away and ignored |

**Given** the catalogue
**When** each is posted
**Then** the counts match and no `500` is returned.

*Why:* `%` and `_` are SQL LIKE wildcards. If `{"text":"%"}` returns everything, the escaping is
broken and the endpoint is an injection surface.

---

### C. Filtering on product fields

#### TC-08: Filter on a product field: `type`

**Objective.** Confirm a simple field filter narrows the caller's catalogue and never widens it.

**Preconditions.** P1–P4.

**Test data.**

| Body | Caller | Expected count | Expected names |
|---|---|---|---|
| `{"filters":[{"field":"type","values":["file"]}]}` | ENV | 6 | CoastalErosionForecast, WaterAbstractionLicences, UnclassifiedDraftDataset, AffordableHousingCompletions, ScotlandLandRegisterExtract, BristolSchoolPlaces |
| same | HEG | 5 | CoastalErosionForecast, AffordableHousingCompletions, HousingNeedSurvey, EmptyHomesRegister, ScotlandLandRegisterExtract |
| same | BCC | 2 | AffordableHousingCompletions, BristolSchoolPlaces |
| `{"filters":[{"field":"type","values":["FILE"]}]}` | ENV | 6 | identical, since comparison is case-insensitive |
| `{"filters":[{"field":"type","values":["topic","file"]}]}` | ENV | 14 | ENV's 15 minus `Bristol_Cycle%Counts`, which has no type |
| `{"filters":[{"field":"type","operator":"neq","values":["topic"]}]}` | ENV | 7 | the 6 files **plus** `Bristol_Cycle%Counts` |

**Given** the catalogue
**When** each is posted
**Then** the counts and names match, and every returned product is a subset of the caller's TC-01 set.

*Why:* the two-value case shows the default operator becoming `in`; the `neq` case shows that a
negative operator also matches products where the value is NULL: a product with no type is "not
topic".

---

#### TC-09: Filter on a field the result does not carry

**Objective.** Confirm that filtering and sorting are **not** limited to the members a summary
returns.

**Preconditions.** P1–P4.

**Test data.** `{"filters":[{"field":"topic","values":["topic.FloodRiskMapZones"]}]}` as ENV.

**Given** that `topic` is absent from every discovery result
**When** the filter is posted
**Then** the response is `200` with exactly **1** product, `FloodRiskMapZones`
**And** that product carries **no** `topic` key.

```bash
discover "$ENV_TOKEN" '{"filters":[{"field":"topic","values":["topic.FloodRiskMapZones"]}]}' \
  | jq '{n: .page.totalElements, keys: (.products[0] | keys)}'
```

**And** the same holds for `source` as ENV:
`{"filters":[{"field":"source","values":["kafka://env/pollution"]}]}` → 1 product
(PollutionIncidentReports) with no `source` key.

*Why:* "I filtered on it, so I should see it" is a natural but wrong expectation. Filtering is
governed by the allowed lists; what is returned is governed by the projection and the masked lists.
They are separate decisions.

---

#### TC-10: A masked field cannot be filtered on

**Objective.** Confirm `allowed − masked`: something withheld from a caller cannot be used as a
filter either.

**Preconditions.** P1–P4.

**Test data.** `{"filters":[{"field":"source","operator":"exists"}]}` as each caller.

**Given** that `source` is masked for BCC but not for ENV or HEG
**When** the request is posted
**Then**

| Caller | Outcome |
|---|---|
| ENV | `200`, **10** products (ENV's 15 minus the 5 with no source: FloodRiskMapZones, BrownfieldLandAvailability, PendingPlanningApplications, UnclassifiedDraftDataset, `Bristol_Cycle%Counts`) |
| HEG | `200`, **9** (HEG's 12 minus the three with no source: FloodRiskMapZones, BrownfieldLandAvailability, `Bristol_Cycle%Counts`) |
| BCC | **`403`**, `reasons: ["filter.field_not_permitted:source"]` |

**And** the same body with `not_exists` as ENV returns the complementary **5**.

*Why:* if a masked field were filterable, a caller could recover its value one query at a time
(`source contains "a"`, `"b"`, …). The rule is not cosmetic.

---

#### TC-11: Filter by owning organisation, both spellings

**Objective.** Confirm `organisation.key` filtering works, and that the `scope` form and the
qualified form are equivalent **for fields**.

**Preconditions.** P1–P4.

**Test data.** As ENV:

```json
{"filters": [{"scope": "organisation", "field": "key", "values": ["HEG"]}]}
```
```json
{"filters": [{"field": "organisation.key", "values": ["HEG"]}]}
```

**Given** ENV's catalogue of 15
**When** either body is posted
**Then** both return the **same 4** products: BrownfieldLandAvailability,
AffordableHousingCompletions, HelpToBuyApplicants, ScotlandLandRegisterExtract
**And** neither returns HousingNeedSurvey or EmptyHomesRegister, which are HEG's but outside ENV's
reach.

**And** `{"filters":[{"field":"organisation.key","values":["heg"]}]}` returns the same 4
(case-insensitive), and `["HEG","BCC"]` returns 9.

*Why:* the filter is AND-ed with the policy's row filter, so asking for another organisation's
products cannot reach past it.

---

#### TC-12: `organisation.name`: default `eq` versus explicit `contains`

**Objective.** Confirm the default operator rules, on the field where they most often surprise.

**Preconditions.** P1–P4.

**Test data.** As ENV:

| # | Body | Expected |
|---|---|---|
| a | `{"filters":[{"field":"organisation.name","values":["Bristol"]}]}` | **0 results**, `200` |
| b | `{"filters":[{"field":"organisation.name","operator":"contains","values":["Bristol"]}]}` | **5**, BCC's products ENV can see |
| c | `{"filters":[{"field":"organisation.name","values":["Bristol City Council (BCC)"]}]}` | **5**, since the whole name matches exactly |
| d | `{"filters":[{"field":"name","values":["bristol"]}]}` | **4**: BristolAirQualitySensors, BristolSchoolPlaces, BristolTemporaryAccommodation, `Bristol_Cycle%Counts` |

**Given** that `organisation.name` is **not** text-searchable and `name` **is**
**When** each is posted
**Then** the results are as above: (a) is an exact-match miss, not an error.

*Why:* a single value defaults to `contains` only on `name` and `description`. Everywhere else it
defaults to `eq`. (a) returning nothing is correct behaviour and the commonest false defect report
against this endpoint.

---

#### TC-13: Filter by who is using a product: `subscribedBy`

**Objective.** Confirm the `subscribedBy` filter compiles to a sub-query over the grants.

**Preconditions.** P1–P4.

**Test data.** As ENV:

| Body | Expected names |
|---|---|
| `{"filters":[{"field":"subscribedBy","operator":"any_of","values":["BCC"]}]}` | FloodRiskMapZones, RiverLevelTelemetry, PollutionIncidentReports, AffordableHousingCompletions (**4**) |
| `{"filters":[{"field":"subscribedBy","values":["BCC"]}]}` | the same 4, since one value defaults to `eq`, which compiles identically here |
| `{"filters":[{"field":"subscribedBy","operator":"any_of","values":["ENV"]}]}` | BrownfieldLandAvailability, AffordableHousingCompletions, HelpToBuyApplicants, BristolAirQualitySensors (**4**) |
| `{"filters":[{"field":"subscribedBy","operator":"exists"}]}` | **9**, every product with any grant (§6.2) |
| `{"filters":[{"field":"subscribedBy","operator":"not_exists"}]}` | **6**, ENV's 15 minus those 9 |

**And** as BCC, any of the above is **`403`** with `filter.field_not_permitted:subscribedBy`
(masked for BCC).

**Given** the grants in §6.2
**When** each is posted as ENV
**Then** the names match.

*Why:* `subscribedBy` is a derived field with no column of its own; it must still filter correctly
and must still obey masking. Cross-check any count against §6.2 before reporting a defect; a grant
added by hand while testing changes these numbers.

---

#### TC-14: Several filters are AND-ed

**Objective.** Confirm multiple filters all have to hold, and that they compose with the policy's
row filter.

**Preconditions.** P1–P4.

**Test data.** As ENV:

```json
{"filters": [
  {"field": "type", "values": ["file"]},
  {"attribute": "quality_designation", "values": ["validated"]}
]}
```

**Given** ENV's 6 file-typed products (TC-08)
**When** the request is posted
**Then** **3** are returned: WaterAbstractionLicences, AffordableHousingCompletions,
ScotlandLandRegisterExtract
**And** adding a third filter `{"field":"organisation.key","values":["HEG"]}` narrows it to **2**
(AffordableHousingCompletions, ScotlandLandRegisterExtract)
**And** adding a fourth that matches nothing
(`{"attribute":"record_unit","values":["person"]}`) gives **0** results, `200`, with
`page.numberOfElements: 0` and `products: []`.

*Why:* there is no `OR` between filters, and no way for a caller to express one. Every additional
filter can only reduce the count.

---

#### TC-15: Every field operator behaves as specified

**Objective.** Cover the operator matrix on fields in one pass.

**Preconditions.** P1–P4. All as ENV.

**Test data and expectations.**

| Operator | Body fragment | Expected |
|---|---|---|
| `eq` | `{"field":"type","operator":"eq","values":["topic"]}` | 8 |
| `neq` | `{"field":"type","operator":"neq","values":["topic"]}` | 7 (includes the NULL-type product) |
| `in` | `{"field":"type","operator":"in","values":["file","topic"]}` | 14 |
| `not_in` | `{"field":"type","operator":"not_in","values":["file","topic"]}` | 1, `Bristol_Cycle%Counts` |
| `any_of` | `{"field":"subscribedBy","operator":"any_of","values":["BCC","HEG"]}` | 7 |
| `none_of` | `{"field":"organisation.key","operator":"none_of","values":["ENV"]}` | 9 |
| `contains` | `{"field":"name","operator":"contains","values":["river"]}` | 1 |
| `exists` | `{"field":"source","operator":"exists"}` | 10 |
| `not_exists` | `{"field":"source","operator":"not_exists"}` | 5 |
| `all_of` | `{"field":"type","operator":"all_of","values":["file"]}` | **`400`**, since `all_of` does not apply to a field |
| `gt` | `{"field":"topic","operator":"gt","values":[5]}` | **`400`**, since ordering operators do not apply to a field |

**Given** ENV's catalogue of 15
**When** each is posted
**Then** the counts and status codes match.

*Why:* `all_of` and the ordering operators are meaningful only on attributes, which can hold several
values or numbers. Rejecting them on a field is a `400`, not a silent no-op.

---

### D. Filtering on policy attributes

#### TC-16: Filter on an attribute every caller may use: `record_unit`

**Objective.** Confirm attribute filtering works for all three callers and is intersected with each
one's row filter.

**Preconditions.** P1–P4.

**Test data.**

| Body | Caller | Expected count | Names |
|---|---|---|---|
| `{"filters":[{"attribute":"record_unit","values":["geographic_area"]}]}` | ENV | 6 | FloodRiskMapZones, RiverLevelTelemetry, CoastalErosionForecast, UnclassifiedDraftDataset, BristolAirQualitySensors, `Bristol_Cycle%Counts` |
| same | BCC | 4 | FloodRiskMapZones, RiverLevelTelemetry, BristolAirQualitySensors, `Bristol_Cycle%Counts` |
| `{"filters":[{"attribute":"record_unit","operator":"in","values":["geographic_area","property"]}]}` | ENV | 9 | the 6 above plus BrownfieldLandAvailability, AffordableHousingCompletions, ScotlandLandRegisterExtract |
| `{"filters":[{"attribute":"record_unit","operator":"not_exists"}]}` | ENV | 0 | every ENV-visible product records a record unit |
| `{"filters":[{"attribute":"quality_designation","operator":"not_exists"}]}` | ENV | 1 | UnclassifiedDraftDataset |

**Given** the attribute values in §6
**When** each is posted
**Then** the names match.

*Why:* an attribute filter compiles to an `EXISTS` over the live-value view with the attribute
*name* bound as a parameter. Adding an attribute to the data makes it filterable with no code change
This is exactly why the name must never be interpolated into SQL.

---

#### TC-17: `identifiability` needs OFFICIAL-SENSITIVE

**Objective.** Confirm an attribute earned by clearance is allowed for ENV and HEG and refused for BCC.

**Preconditions.** P1–P4.

**Test data.** `{"filters":[{"attribute":"identifiability","values":["non_personal"]}]}`

**Given** ENV at SECRET, HEG at OFFICIAL-SENSITIVE, BCC at OFFICIAL
**When** the request is posted as each
**Then**

| Caller | Outcome |
|---|---|
| ENV | `200`, **5**: FloodRiskMapZones, RiverLevelTelemetry, CoastalErosionForecast, BristolAirQualitySensors, `Bristol_Cycle%Counts` |
| HEG | `200`, **the same 5**, all of them within HEG's catalogue too |
| BCC | **`403`**, `reasons: ["filter.attribute_not_permitted:identifiability"]` |

**And** `{"filters":[{"attribute":"identifiability","values":["directly_identifiable"]}]}` as ENV
returns **4** (WaterAbstractionLicences, HelpToBuyApplicants, PendingPlanningApplications,
BristolTemporaryAccommodation) and as HEG returns **1**, only `HelpToBuyApplicants`, which is
HEG's **own** product. An organisation always discovers its own products whatever their attributes,
so the three belonging to others are outside HEG's catalogue and the filter cannot reach them.

*Why:* the refusal is a *policy* decision, visible in advance in `policy.filterableAttributes`
(TC-03). BCC's list does not contain `identifiability`.

---

#### TC-18: `population_risk_tags` is filterable by ENV only (SECRET)

**Objective.** Confirm the sensitivity rule: an attribute flagged `sensitive` in the catalogue is
withheld, and therefore unfilterable, below SECRET.

**Preconditions.** P1–P4.

**Test data.**

| Body | Caller | Expected |
|---|---|---|
| `{"filters":[{"attribute":"population_risk_tags","operator":"any_of","values":["children"]}]}` | ENV | `200`, **2**: HelpToBuyApplicants, BristolSchoolPlaces |
| same | HEG | **`403`**, `filter.attribute_not_permitted:population_risk_tags` |
| same | BCC | **`403`**, the same reason |
| `{"filters":[{"attribute":"population_risk_tags","operator":"all_of","values":["domestic_abuse_survivors","protected_witnesses"]}]}` | ENV | `200`, **1**: PendingPlanningApplications |
| `{"filters":[{"attribute":"population_risk_tags","operator":"none_of","values":["children"]}]}` | ENV | `200`, **13**, ENV's 15 minus the two tagged `children`; products with no tags at all **are** included |

**Given** that `population_risk_tags` is flagged `sensitive` in `policy_attribute_definition`
**When** each is posted
**Then** the outcomes match.

*Why:* two mechanisms produce the same refusal here: the rule does not grant the attribute below
SECRET, and the service independently refuses any attribute the catalogue flags sensitive while the
contract masks sensitive attributes. Both give the identical reason, so the caller cannot tell them
apart, and neither can be bypassed by the other failing. `all_of` and `none_of` also demonstrate
multi-valued attribute semantics: `none_of` matches products carrying no tags at all.

---

#### TC-19: `temporal_resolution` is filterable by HEG only (purpose)

**Objective.** Confirm that an entitlement can come from a *purpose* rather than a clearance, so
the most-cleared caller is not automatically the most capable one.

**Preconditions.** P1–P4.

**Test data.** `{"filters":[{"attribute":"temporal_resolution","values":["annual"]}]}`

**Given** HEG holds `statistical_analysis` and ENV (SECRET) does not
**When** the request is posted
**Then**

| Caller | Outcome |
|---|---|
| HEG | `200`, **5**: CoastalErosionForecast, BrownfieldLandAvailability, AffordableHousingCompletions, HousingNeedSurvey, ScotlandLandRegisterExtract |
| ENV | **`403`**, `filter.attribute_not_permitted:temporal_resolution`, *despite SECRET clearance* |
| BCC | **`403`**, the same reason |

*Why:* this is the clearest demonstration that entitlement is not a single ladder. Clearance decides
identifiability and masking; purpose decides quality and the research attributes. An ENV `403` here
is correct, and reporting it as a privilege defect is the mistake this case exists to prevent.

---

#### TC-20: Organisation-scoped attribute filter, and the two spellings

**Objective.** Confirm an attribute of the owning organisation can be filtered on, and that the
qualified spelling means exactly the same as the `scope` form.

**Preconditions.** P1–P4.

**Test data.** As ENV:

| # | Body | Expected |
|---|---|---|
| a | `{"filters":[{"scope":"organisation","attribute":"responsibility_areas","operator":"any_of","values":["housing_delivery"]}]}` | **4**, HEG's products ENV can see (BrownfieldLandAvailability, AffordableHousingCompletions, HelpToBuyApplicants, ScotlandLandRegisterExtract) |
| b | `{"filters":[{"attribute":"organisation.responsibility_areas","operator":"any_of","values":["housing_delivery"]}]}` | **the same 4**, byte-identical to (a) |
| c | `{"filters":[{"scope":"organisation","attribute":"jurisdictions","operator":"any_of","values":["Bristol"]}]}` | **5**, BCC's products ENV can see |

**Given** the organisation attributes in §5.2
**When** each is posted
**Then** (a) and (b) return the same four products, and (c) returns the count above.

*Why:* (a) and (b) are two spellings of one thing, and the endpoint must not have a spelling that
looks accepted and quietly matches nothing. A `200` with 0 results for (b) where (a) returns four is
the regression this case exists to catch: the qualified name reaching the *product* scope, where no
such attribute exists. (For *fields*, TC-11 asserts the same equivalence.)

---

#### TC-21: Every attribute operator behaves as specified

**Objective.** Cover the operator matrix on attributes.

**Preconditions.** P1–P4. All as ENV.

| Operator | Body fragment | Expected |
|---|---|---|
| `eq` | `{"attribute":"quality_designation","operator":"eq","values":["validated"]}` | 10 |
| `neq` | `{"attribute":"quality_designation","operator":"neq","values":["validated"]}` | 5: the 3 provisional, the 1 experimental, **and** UnclassifiedDraftDataset, which records none |
| `in` | `{"attribute":"quality_designation","operator":"in","values":["validated","provisional"]}` | 13 |
| `any_of` | `{"attribute":"record_unit","operator":"any_of","values":["person","household"]}` | 4 |
| `all_of` | `{"attribute":"population_risk_tags","operator":"all_of","values":["domestic_abuse_survivors","protected_witnesses"]}` | 1 |
| `none_of` | `{"attribute":"record_unit","operator":"none_of","values":["person"]}` | 13 |
| `contains` | `{"attribute":"identifiability","operator":"contains","values":["personal"]}` | 5, matching `non_personal` |
| `exists` | `{"attribute":"identifiability","operator":"exists"}` | 14 |
| `not_exists` | `{"attribute":"identifiability","operator":"not_exists"}` | 1, UnclassifiedDraftDataset |
| `gt` (numeric) | `{"attribute":"record_unit","operator":"gt","values":[5]}` | `200`, **0 results**, since no value of that attribute is a number |
| `gt` (non-numeric value) | `{"attribute":"record_unit","operator":"gt","values":["abc"]}` | **`400`**, since an ordering operator needs a number |

**Given** the attribute values in §6
**When** each is posted
**Then** the counts and status codes match.

> Verify each count against §6 as you go. These are derived from the sample data, and a single
> edited attribute row moves several of them at once.

*Why:* the ordering rows are the interesting pair. A numeric operator with a numeric value compiles
and returns nothing (no numeric attribute exists in the sample data); with a non-numeric value it is
refused outright rather than silently comparing text.

---

#### TC-22: An unknown attribute value returns nothing, not an error

**Objective.** Confirm a value outside an attribute's `allowed_values` is treated as data.

**Preconditions.** P1–P4.

**Test data.** `{"filters":[{"attribute":"record_unit","values":["spaceship"]}]}` as ENV.

**Given** `record_unit` has a declared value set that does not include `spaceship`
**When** the request is posted
**Then** the response is `200` with `totalElements: 0` and `products: []`
**And** it is **not** a `400`, and **not** a `403`.

*Why:* validating the value against the vocabulary would turn the endpoint into a probe of that
vocabulary; a caller could enumerate every legal value by watching which ones are rejected.

---

### E. Sorting

#### TC-23: Sort by a field, ascending and descending

**Objective.** Confirm sorting works on a sortable field in both directions and that the default is
`name` ascending.

**Preconditions.** P1–P4.

**Test data.** As ENV.

| Body | Expected |
|---|---|
| `{}` | sorted by `name` ascending: first `AffordableHousingCompletions`, last `WaterAbstractionLicences` |
| `{"sort":[{"field":"name","direction":"desc"}]}` | the exact reverse: first `WaterAbstractionLicences`, last `AffordableHousingCompletions` |
| `{"sort":[{"field":"name","direction":"DESC"}]}` | identical, since direction is case-insensitive |
| `{"sort":[{"field":"organisation.key"},{"field":"name","direction":"desc"}]}` | grouped by organisation key ascending (BCC, ENV, HEG), each group's names descending |

**Given** ENV's 15 products
**When** each is posted
**Then** the ordering is as described, and the **set** of names is identical in every case.

> Sorting is case-insensitive (`LOWER(column)`). The relative position of `Bristol_Cycle%Counts`
> against `BristolAirQualitySensors` depends on the **database collation**, and a collation that
> ignores punctuation orders them differently from `C`. Do not assert on that one pair; assert that
> the list is non-decreasing and that the first and last entries are as above.

*Why:* ordering must not change *which* products are returned. If a sort key changes the count,
the sort is leaking into the `WHERE` clause.

---

#### TC-24: Sort by a policy attribute

**Objective.** Confirm an attribute can be a sort key.

**Preconditions.** P1–P4.

**Test data.** `{"sort":[{"attribute":"temporal_resolution","direction":"asc"}],"size":50}` as HEG
(the only caller permitted that attribute).

**Given** HEG's 12 products
**When** the request is posted
**Then** they are ordered by the attribute value alphabetically: `annual` (5), `daily` (2),
`event_level` (2), `hourly` (2), `monthly` (1)
**And** the count is still 12.

**And** `{"sort":[{"attribute":"quality_designation","direction":"desc"}]}` as ENV orders
`validated`, then `provisional`, then `experimental`, with **UnclassifiedDraftDataset last**, since it
records no quality, and nulls sort last in both directions.

*Why:* an attribute sort is a correlated sub-query, not a column. Getting the same set in a
different order proves it is applied as an `ORDER BY` and not as a filter.

---

#### TC-25: Ties break by id, and nulls sort last

**Objective.** Confirm ordering is total and stable across pages.

**Preconditions.** P1–P4.

**Test data.** As BCC: `{"sort":[{"field":"type"}],"size":20}` and the same with
`"direction":"desc"`.

**Given** BCC's 8 products, of which two are `file`, five are `topic` and one has no type
**When** both are posted
**Then** ascending gives `file` (2), then `topic` (5), then `Bristol_Cycle%Counts` **last**
**And** descending gives `topic` (5), then `file` (2), then `Bristol_Cycle%Counts` **still last**
**And** within each group of equal `type` the order is identical between the two requests
(ties break by ascending id)
**And** re-running either request returns the same order every time.

*Why:* without a total order, paging repeats and skips products. If the same request returns a
different order twice, paging cannot be trusted.

---

#### TC-26: Sort on a filterable but unsortable field

**Objective.** Confirm `description` and `subscribedBy` can be filtered on but not sorted on.

**Preconditions.** P1–P4.

**Test data.** As ENV:

| Body | Expected |
|---|---|
| `{"sort":[{"field":"description"}]}` | **`400`**: `Invalid search criteria: Products cannot be sorted by 'description'` |
| `{"sort":[{"field":"subscribedBy"}]}` | **`400`**, the same shape of message |
| `{"filters":[{"field":"description","operator":"contains","values":["flood"]}]}` | `200`, since the same field filters perfectly well |

**Given** that both fields are in ENV's `filterableFields`
**When** each is posted
**Then** the sorts are `400` and the filter is `200`.

*Why:* this is a `400`, not a `403`, because the caller is *permitted* the field, the service simply cannot
order by it. Distinguishing the two is the point: a `403` here would wrongly suggest a policy
restriction. Note the response `policy.filterableFields` is a combined filter/sort list and does not
express this distinction; that is why it needs a test.

---

#### TC-27: Sort on a masked field

**Objective.** Confirm a masked field is refused as a sort key, with the sort-specific reason.

**Preconditions.** P1–P4.

**Test data.** `{"sort":[{"field":"source"}]}` as BCC.

**Given** `source` is masked for BCC
**When** the request is posted
**Then** the response is **`403`** with
`{"status":403,"message":"Access denied by policy","reasons":["sort.not_permitted:source"],"errorId":"…"}`
**And** the same request as ENV is `200`.

*Why:* the reason code is `sort.not_permitted`, distinct from `filter.field_not_permitted`, so a log
search can tell which part of the request was at fault.

---

### F. Paging

#### TC-28: Paging walks the catalogue exactly once

**Objective.** Confirm pages neither repeat nor skip, and that totals are stable.

**Preconditions.** P1–P4.

**Test data.** As ENV: `{"size":4,"page":0}` … `{"size":4,"page":3}`.

**Given** ENV's 15 products
**When** all four pages are fetched
**Then**

| page | `numberOfElements` | `size` | `totalElements` | `totalPages` |
|---|---|---|---|---|
| 0 | 4 | 4 | 15 | 4 |
| 1 | 4 | 4 | 15 | 4 |
| 2 | 4 | 4 | 15 | 4 |
| 3 | **3** | 4 | 15 | 4 |

**And** the concatenation of the four pages is exactly the 15 names of TC-01, each once.

```bash
for p in 0 1 2 3; do discover "$ENV_TOKEN" "{\"size\":4,\"page\":$p}" | jq -r '.products[].name'; done \
  | sort | uniq -d     # must print nothing
```

*Why:* paging is done by the database on the same conditions the count uses, so the totals and the
pages must agree exactly. A duplicate across pages means the ordering is not total (TC-25).

---

#### TC-29: `numberOfElements` is not `size`

**Objective.** Confirm the two counters answer different questions.

**Preconditions.** P1–P4.

**Test data.**

| Body | Caller | `size` | `numberOfElements` |
|---|---|---|---|
| `{}` | ENV | 20 | 15 |
| `{"size":4,"page":3}` | ENV | 4 | 3 |
| `{"text":"no-such-product-anywhere"}` | ENV | 20 | 0 |
| `{"size":1}` | BCC | 1 | 1 |

**Given** each request
**When** it is posted
**Then** `.products | length` **always** equals `.page.numberOfElements`
**And** `size` is the page size applied, which may exceed the number of products carried.

*Why:* a mismatch between `.products | length` and `numberOfElements` would mean products were
removed after the page was assembled, which is precisely what this design forbids, because
withheld data is never read in the first place.

---

#### TC-30: Page size is clamped to `maxPageSize`, not refused

**Objective.** Confirm an over-large page size is reduced silently and reported honestly.

**Preconditions.** P1–P4.

**Test data.** `{"size":500}` as each caller.

**Given** the per-caller caps of §5.4
**When** the request is posted
**Then**

| Caller | `page.size` | `page.numberOfElements` |
|---|---|---|
| ENV | **100** | 15 |
| HEG | **50** | 12 |
| BCC | **20** | 8 |

**And** the status is `200` in every case: an over-large size is never a `400` or a `403`
**And** `{"size":3}` as BCC returns `size: 3` (a request below the cap is honoured unchanged).

*Why:* the response reports the size that was *applied*, so a client can tell it was clamped. The
cap is part of the policy contract (`policy.maxPageSize`), which is why it differs per caller.

---

#### TC-31: A page beyond the end

**Objective.** Confirm an out-of-range page is empty, not an error.

**Preconditions.** P1–P4.

**Test data.** `{"size":4,"page":99}` as ENV.

**Given** ENV's 15 products fill 4 pages
**When** page 99 is requested
**Then** the response is `200` with `products: []`, `numberOfElements: 0`
**And** `totalElements: 15` and `totalPages: 4` are unchanged.

*Why:* totals describe the whole result, not the page. They must not become 0 just because this
page is empty.

---

### G. Refusals by policy, 403

Every refusal in this section has the same body shape:

```json
{"status": 403, "message": "Access denied by policy", "reasons": ["<code>"], "errorId": "…"}
```

`reasons` codes are stable and safe to assert on. A reason beginning `policy.` or `dispatch.` must
**never** appear in a response body; those stay in the log.

#### TC-32: `filter.field_not_permitted:<name>`

**Objective.** Confirm a refused field filter names itself.

**Preconditions.** P1–P4.

**Test data.** `{"filters":[{"field":"source","values":["x"]}]}` as BCC.

**Given** `source` is masked for BCC
**When** the request is posted
**Then** `403` with `reasons: ["filter.field_not_permitted:source"]`
**And** the name after the colon is the name the caller sent, spelt as they sent it
**And** the same request as ENV or HEG is `200`.

*Why:* the code before the colon is the stable audit key; the part after it is what the caller must
change.

---

#### TC-33: `filter.attribute_not_permitted:<name>`

**Objective.** Confirm a refused attribute filter names itself, and that fields and attributes are
refused with distinct codes.

**Preconditions.** P1–P4.

**Test data.** `{"filters":[{"attribute":"identifiability","values":["non_personal"]}]}` as BCC.

**Given** BCC's allowed attribute list has no `identifiability`
**When** the request is posted
**Then** `403` with `reasons: ["filter.attribute_not_permitted:identifiability"]`
**And** posting the same **name as a field** (`{"field":"identifiability","values":["x"]}`) gives
`filter.field_not_permitted:identifiability`, a different code for the same string.

*Why:* the caller states whether it means a field or an attribute, so nothing is inferred from the
name, and the refusal says which kind was refused.

---

#### TC-34: `sort.not_permitted:<name>`

Covered by [TC-27](#tc-27--sort-on-a-masked-field). Also verify an *attribute* sort key:
`{"sort":[{"attribute":"identifiability"}]}` as BCC → `403`,
`reasons: ["sort.not_permitted:identifiability"]`.

*Why:* sorting reveals ordering information about a value, so it is gated by exactly the same list
as filtering.

---

#### TC-35: `text.not_permitted`

**Objective.** Confirm a free-text term is refused when the caller may search no text column.

**Preconditions.** P1–P4, **plus a temporary policy edit**, since no shipped organisation can trigger
this, because `name` and `description` are visible to every clearance.

**Test data.** In `docker/opa/policies/lib/product.rego`, add `"name"` and `"description"` to the
lowest branch of `hidden_fields`:

```rego
} else := ["consumers", "policyAttributes", "source", "subscribedBy", "name", "description"]
```

Then `docker compose restart opa` and post `{"text":"flood"}` as BCC.

**Given** a caller for whom every text-searchable field is masked
**When** a text term is sent
**Then** the response is `403` with
`reasons: ["filter.field_not_permitted:description", "text.not_permitted"]`
**And** the same caller sending **no** `text` is still `200`
**And** `policy.textSearchFields` is `[]` for that caller.

**Revert the policy file and restart OPA before continuing.**

*Why:* asking for a text search that can match nothing would return an empty page indistinguishable
from "no such product". Refusing says so. Both reasons appear because requesting text is implicitly
requesting the `description` field.

---

#### TC-36: An unknown name is refused identically to a forbidden one

**Objective.** Confirm the endpoint cannot be used to enumerate the field or attribute vocabulary.

**Preconditions.** P1–P4.

**Test data.** As BCC, three requests:

| # | Body | |
|---|---|---|
| a | `{"filters":[{"attribute":"identifiability","values":["x"]}]}` | exists, forbidden for BCC |
| b | `{"filters":[{"attribute":"no_such_attribute_at_all","values":["x"]}]}` | does not exist anywhere |
| c | `{"filters":[{"field":"nonsense_field","values":["x"]}]}` | does not exist anywhere |

**Given** the three requests
**When** each is posted
**Then** all three return `403`
**And** the bodies are **identical apart from the name in `reasons` and the `errorId`**, the same
status, same message, same reason code prefix
**And** none of them is a `400` or a `404`.

```bash
for a in identifiability no_such_attribute_at_all; do
  discover "$BCC_TOKEN" "{\"filters\":[{\"attribute\":\"$a\",\"values\":[\"x\"]}]}" | jq 'del(.errorId)'
done
```

*Why:* if an unknown name gave a `400` while a forbidden one gave a `403`, a caller could map the
entire vocabulary, and by extension infer what other organisations are allowed. This is the
non-disclosure guarantee of the endpoint, and it is cheap to break accidentally.

---

#### TC-37: Several refusals are reported at once

**Objective.** Confirm every refused criterion is named in one response.

**Preconditions.** P1–P4.

**Test data.** As BCC:

```json
{"filters": [
  {"field": "source", "values": ["x"]},
  {"attribute": "identifiability", "values": ["y"]}
],
 "sort": [{"field": "subscribedBy"}]}
```

**Given** three separately refusable criteria
**When** the request is posted
**Then** `403` with all three reasons present, sorted:

```json
["filter.attribute_not_permitted:identifiability",
 "filter.field_not_permitted:source",
 "sort.not_permitted:subscribedBy"]
```

*Why:* a caller fixing one refusal at a time needs three round trips. Reporting them together is a
usability requirement, and a response carrying only the first is a defect.

---

#### TC-38: A filter cannot reach past the row filter

**Objective.** Confirm that asking for a product the caller may not discover is an empty result, not
a refusal.

**Preconditions.** P1–P4.

**Test data.**

| Body | Caller | Expected |
|---|---|---|
| `{"text":"WaterAbstractionLicences"}` | BCC | `200`, **0 results** |
| `{"filters":[{"field":"name","operator":"eq","values":["HousingNeedSurvey"]}]}` | ENV | `200`, **0 results** |
| `{"text":"UnclassifiedDraftDataset"}` | HEG | `200`, **0 results** |
| `{"text":"UnclassifiedDraftDataset"}` | ENV | `200`, **1 result**, its owner |

**Given** the catalogue
**When** each is posted
**Then** the counts match and none is a `403` or a `404`.

*Why:* a `403` here would confirm the product exists. "Not yours" and "not there" must look the
same. Note these use *permitted* criteria; the refusals of TC-32/33 are about the criterion, not
the product.

---

#### TC-39: Authentication, role and PDP failures

**Objective.** Confirm the gates before and around policy.

**Preconditions.** P1–P4 except as stated.

| # | Condition | Expected |
|---|---|---|
| a | no `Authorization` header | `401` |
| b | a token without the `product_discovery` role | `403`, message `"Access denied: insufficient permissions for this operation"`, **no `reasons` field** |
| c | a token with the role but **no `organisation` claim** | `403`, `"Access denied by policy"`, `reasons: ["organisation.missing"]` |
| d | OPA stopped (`docker compose stop opa`), policy on | `403` for every request, **no `reasons`**, since the PDP fails closed |
| e | `GET` instead of `POST` on `/api/v1/product/discover` | `405` |
| f | `Content-Type: text/plain` with a JSON body | `415` |

**Given** each condition
**When** a valid empty body is posted
**Then** the status and body are as above
**And** in (b) the message is the *role* message, distinguishable at a glance from the policy one.

*Why:* four different `403`s exist and they mean different things. Telling them apart by their
message and `reasons` is the fastest triage step on this endpoint. Restart OPA after (d).

---

### H. Malformed requests, 400

Every `400` body is `{"status":400,"message":"…","errorId":"…"}` with **no** `reasons` field.

#### TC-40: No body at all is valid

**Objective.** Confirm an absent body is not an error.

**Preconditions.** P1–P4.

**Test data.**

```bash
curl -sk --cert client.crt --key client.key -X POST \
  'https://localhost:8090/api/v1/product/discover' \
  -H "Authorization: Bearer $ENV_TOKEN" -H 'Content-Type: application/json'
```

**Given** no request body
**When** the request is posted
**Then** `200`, identical to posting `{}`, ENV's first page of 15.

*Why:* the body is optional; every member of it has a default.

---

#### TC-41: Body-shape 400s, refused before policy

**Objective.** Confirm the validation limits of §3.

**Preconditions.** P1–P4. All as ENV.

| # | Body | Expected message |
|---|---|---|
| a | `{not-json` | `Invalid request body: malformed JSON` |
| b | `{"page":-1}` | `Invalid request: page must be greater than or equal to 0` |
| c | `{"size":0}` | `Invalid request: size must be greater than or equal to 1` |
| d | `{"text":"<256 characters>"}` | `Invalid request: text size must be between 0 and 255` |
| e | 51 filter entries | `Invalid request: filters size must be between 0 and 50` |
| f | 4 sort entries | `Invalid request: sort size must be between 0 and 3` |
| g | `{"filters":[{"field":"name","operator":"sounds_like","values":["v"]}]}` | `Invalid request body: 'filters[0].operator' has an invalid value` |
| h | `{"sort":[{"field":"name","direction":"sideways"}]}` | `Invalid request: …must be 'asc' or 'desc'` |
| i | `{"filters":[{"scope":"planet","field":"name","values":["v"]}]}` | `Invalid request body: 'filters[0].scope' has an invalid value` |
| j | `{"filters":[{"field":"name","values":[1,2,3,…51 values]}]}` | `Invalid request: …values size must be between 0 and 50` |

**Given** each body
**When** it is posted
**Then** the status is `400` and the message matches
**And** **no `reasons` field is present**; these are not policy refusals
**And** each is a `400` **for every caller**, including BCC, because the body is validated before
the policy decision is taken.

*Why:* these are refused when the handler's arguments are bound, which happens before the
enforcement point runs. They are therefore independent of who is calling.

---

#### TC-42: Criteria 400s, decided *after* policy

**Objective.** Confirm the semantic criteria checks, and that a policy refusal takes precedence
over them.

**Preconditions.** P1–P4. All as ENV.

| # | Body | Expected |
|---|---|---|
| a | `{"filters":[{"field":"name","attribute":"record_unit","values":["v"]}]}` | `400`: `Invalid search criteria: A filter names exactly one of 'field' and 'attribute'` |
| b | `{"filters":[{"values":["v"]}]}` | `400`, the same message (neither named) |
| c | `{"sort":[{"field":"name","attribute":"record_unit"}]}` | `400`: `A sort key names exactly one of 'field' and 'attribute'` |
| d | `{"filters":[{"field":"name","values":[{"a":1}]}]}` | `400`: `The values of 'name' must be text, numbers or booleans` |
| e | `{"filters":[{"field":"name","values":["<256 characters>"]}]}` | `400`: `A value of 'name' is longer than 255 characters` |
| f | `{"filters":[{"field":"name","operator":"eq","values":["a","b"]}]}` | `400`: `Operator 'eq' on 'name' cannot take 2 value(s)` |
| g | `{"filters":[{"field":"name","operator":"exists","values":["a"]}]}` | `400`: `Operator 'exists' … cannot take 1 value(s)` |
| h | `{"filters":[{"field":"topic","operator":"gt","values":[5]}]}` | `400`: `Operator 'gt' does not apply to the field 'topic'` |
| i | `{"filters":[{"attribute":"record_unit","operator":"gt","values":["abc"]}]}` | `400`: `Operator 'gt' on 'record_unit' needs a number` |

**And then the precedence check:**

| Body | Caller | Expected |
|---|---|---|
| `{"filters":[{"field":"name","attribute":"record_unit","values":["v"]}]}` | ENV | **`400`**, since both names are permitted for ENV, so the request reaches the handler |
| `{"filters":[{"field":"name","attribute":"no_such_thing","values":["v"]}]}` | ENV | **`403`**, `filter.attribute_not_permitted:no_such_thing`; the policy judges the raw body first |
| `{"filters":[{"field":"name","attribute":"record_unit","values":["v"]}]}` | BCC | **`400`**, since both are in BCC's lists too |
| `{"filters":[{"field":"source","attribute":"record_unit","values":["v"]}]}` | BCC | **`403`**, `filter.field_not_permitted:source` |

**Given** each body
**When** it is posted
**Then** the status matches.

*Why:* this is the single most confusing part of the endpoint for a test author. A malformed filter
that also names something forbidden is a `403`, because policy sees the body as posted, before the
handler validates it. **When writing a `400` test, choose names the caller is permitted**, or the
`403` will arrive first and the test will look broken when it is not.

---

### I. Policy switched off

#### TC-43: With policy off, every caller sees the whole catalogue

**Objective.** Confirm the supported switched-off mode: nothing restricted, and the response says so.

**Preconditions.** P1, P2, P4, **P5** (`application.opa.enabled=false`, service restarted). OPA may
be stopped entirely, since nothing should call it.

**Test data.** `{}` as each token; then the filters of TC-17 to TC-19.

**Given** policy enforcement is off
**When** an empty body is posted as each caller
**Then** all three return `totalElements: 17`
**And** the `policy` block is **absent**: not `null`, not filled in with defaults
**And** the startup log carries the documented `WARN` that policy will not be evaluated
**And** `{"filters":[{"attribute":"identifiability","values":["directly_identifiable"]}]}` as **BCC**
is now `200` with 4 products (WaterAbstractionLicences, HelpToBuyApplicants,
PendingPlanningApplications, BristolTemporaryAccommodation), the identical request is a `403` with
policy on (TC-17)
**And** `{"size":500}` clamps to **100** for every caller
(`ProductSearchContract.DEFAULT_MAX_PAGE_SIZE`, the cap when nothing else sets one), not to a policy
cap
**And** every `400` of TC-41 is still a `400`, since request validation is the service's own.

*Why:* with the switch off no decision is taken at all, so there is nothing truthful to report in a
`policy` block. A block full of defaults would be the service asserting a verdict nobody reached.

---

#### TC-44: Switching policy back on restores the contract

**Objective.** Confirm the switch is the only difference.

**Preconditions.** P1–P4 (restart with `application.opa.enabled=true`).

**Given** TC-43 has just been run
**When** the service is restarted with policy on and TC-01 and TC-17 are repeated
**Then** the counts return to 15 / 12 / 8
**And** BCC's `identifiability` filter becomes `403` again
**And** the `policy` block reappears.

*Why:* if results do not change when the switch is flipped, either the switch is not taking effect
or the contract is not being applied, and a search running unrestricted while policy is *meant* to
be on is the most serious failure mode this endpoint has. (It is designed to be impossible: a
missing decision with the switch on is refused with `policy.enforcement_missing` in the log, never
answered.)

---

## 9. Edge cases

Each is cheap to run and each has caught, or is designed to catch, a real class of defect.

| # | Case | Request | Expected | Why |
|---|---|---|---|---|
| E1 | **LIKE metacharacters in a term** | `{"text":"Bristol_Cycle%Counts"}` as BCC | exactly 1 product | `%` and `_` must be escaped and matched literally |
| E2 | **A bare wildcard** | `{"text":"%"}` as BCC | **0 results**, `200` | if this returns everything, `%` is reaching the database as a wildcard |
| E3 | **Metacharacters in a filter value** | `{"filters":[{"field":"name","operator":"contains","values":["_Cycle%"]}]}` as BCC | 1 product | filter values go through the same escaping as the text term |
| E4 | **SQL-injection-shaped input** | `{"text":"' OR 1=1 --"}`; `{"filters":[{"field":"name","values":["'; DROP TABLE product; --"]}]}`; `{"filters":[{"attribute":"'; --","values":["x"]}]}` | `200` with 0 results, or `403` for the third (unknown attribute name); **never** a `500`, and `SELECT count(*) FROM mn.product` is still 17 | values *and attribute names* are bound parameters |
| E5 | **A value outside `allowed_values`** | `{"filters":[{"attribute":"record_unit","values":["spaceship"]}]}` | `200`, 0 results | validating against the vocabulary would let a caller enumerate it (TC-22) |
| E6 | **A product with a NULL description** | `{"text":"Cycle"}` as BCC | 1 product, whose `description` key is **absent** and whose `type` key is **absent** | a null column must not match a text search and must not serialise as `null` or `""` |
| E7 | **A product missing its attributes** | `{"text":"UnclassifiedDraftDataset"}` as ENV / HEG / BCC | **1 / 0 / 0** | it records no `identifiability` and no `quality_designation`; a missing attribute must fail closed. If HEG or BCC can see it, an unclassified product is discoverable by default, a serious defect |
| E8 | **Empty `values` on a positive operator** | `{"filters":[{"field":"type","values":[]}]}` or `{"filters":[{"field":"type"}]}` | `200`, **0 results** | "in nothing" matches nothing; it must not be ignored, which would widen the result |
| E9 | **Empty `values` on a negative operator** | `{"filters":[{"attribute":"record_unit","operator":"none_of","values":[]}]}` | `200`, the caller's **full** catalogue | "none of nothing" is true of everything, the mirror of E8 |
| E10 | **An empty `filters` / `sort` array** | `{"filters":[],"sort":[]}` | identical to `{}` | an empty list is not a criterion |
| E11 | **Duplicate filters on the same field** | two identical `{"field":"type","values":["file"]}` entries | same as one | AND-ing a condition with itself changes nothing |
| E12 | **Contradictory filters** | `type eq file` AND `type eq topic` | `200`, 0 results | filters are AND-ed; there is no implicit `OR` |
| E13 | **Unicode and accents in a term** | `{"text":"Ärger"}` | `200`, 0 results, no `500` | the term is bound, not interpolated |
| E14 | **An id from discover works against view** | take `.products[0].id` from any discover response and `GET /api/v1/product/{id}` with the same token | `200`, and the same `name` | "you can view exactly what you can discover"; see [product-view.md](product-view.md) |
| E15 | **An id you cannot discover** | take an id from ENV's response that is absent from BCC's, `GET` it as BCC | **`404`**, identical to a non-existent id such as `999999` | a `200` would make the id a way around discovery's filtering |
| E16 | **The qualified spelling of an organisation attribute** | `{"filters":[{"attribute":"organisation.jurisdictions","operator":"any_of","values":["England"]}]}` as ENV | the **same** result as the `scope` form | the two spellings must not diverge ([§5.3](#53-naming-an-organisation-scoped-field-or-attribute)); a qualified name that matches nothing is silently wrong, so assert it against its twin |
| E17 | **Filtering on `id`** | `{"filters":[{"field":"id","values":[1]}]}` | **`403`**, `filter.field_not_permitted:id` | no policy grants `id`, so a caller cannot page the catalogue by id to probe what exists |
| E18 | **Sensitive attributes never appear** | `discover "$HEG_TOKEN" '{}' \| grep -c authorised_classifications` | `0` | discovery returns no attribute maps at all, so this must be 0 for every caller |
| E19 | **Producer connection details never appear** | `discover "$ENV_TOKEN" '{}' \| grep -Ec '"host"\|"port"\|"idp_client_id"\|"destination"'` | `0` | those belong to the configuration API |
| E20 | **The same request twice** | any body, posted twice | byte-identical apart from nothing (there is no `errorId` in a `200`) | results must be a function of caller and criteria only |

### Things that should never happen

Treat any of these as a defect whatever else is passing:

1. A `500` from **any** input in this document.
2. A masked member (`source`, `consumers`, `subscribedBy`, `attributes`) present in a discovery
   result for any caller, since a summary carries none of them regardless of policy.
3. `totalElements` larger than the number of products the caller can actually page through.
4. A reason beginning `policy.` or `dispatch.` in a response body.
5. UnclassifiedDraftDataset returned to HEG or BCC.
6. Identical results for ENV, HEG and BCC with the `policy` block present.
7. A `400` where TC-36 requires a `403`, for a name that does not exist.

## 10. Defect triage

Before raising a defect, work down this list; most first failures are one of the first three rows.

| Symptom | Most likely cause |
|---|---|
| all three callers return the same products | the same token was sent three times (P4), or policy is off (check for the `policy` block) |
| every request is `403 "Access denied by policy"` | OPA is down (it fails closed), or the token has no `organisation` claim; check `reasons` for `organisation.missing` |
| every request is `403 "Access denied: insufficient permissions"` | the `product_discovery` role is missing from the token, a role failure, not a policy one |
| a filter returns nothing | default operator: `eq` where you meant `contains` (TC-12); or a value outside the attribute's vocabulary (TC-22) |
| a count differs from this document by one or two | re-derive it from §6 before reporting. A single edited attribute row moves several counts |
| a `403` you expected to be a `400` | the policy judged the body before the handler validated it (TC-42) |
| the log says `policy.enforcement_missing` | policy is on but no decision reached the handler, a wiring fault to report, not a caller problem |
| you want to see the decision | set `application.opa.log-input: true` and `log-output: true` (the `dev` profile does); the output log shows the contract that came back |
| you want to see the query | set `logging.level.uk.gov.dbt.ndtp.ia.node.management.service.data.impl.ProductDiscoveryServiceImpl: DEBUG` to log the compiled SQL and its bound parameters |

To ask the PDP the same question the service asks, without the service, see
[`DISCOVERY_TEST_SCENARIOS.md` §9](../../DISCOVERY_TEST_SCENARIOS.md#9-troubleshooting). The
`row_filter` that comes back is literally the `WHERE` clause of the search.
