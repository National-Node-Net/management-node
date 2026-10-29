# Product Discover Filter: Test Requirement Specification

**Repository:** `management-node`
**Endpoint:** `POST /api/v1/product/discover/filter`
**Role required:** `product_discovery`
**Policy rule:** `policies.product.discover`, reached via the route `route-product-discover-filter`
(`docker/opa/policies/routing/data.json`)
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`

---

A formal test specification for a manual testing team, in the structure of
[`product-discover.md`](product-discover.md). It states what the API does, what decides its
behaviour, then gives numbered test cases (**Objective / Preconditions / Test data /
Given-When-Then**), and finally the edge cases.

> ## Read together with [`product-discover.md`](product-discover.md)
>
> This endpoint shares its request grammar, its validation, its policy rule and every refusal with
> `POST /api/v1/product/discover`. This document does not repeat discover's full operator matrix,
> its 400/403 catalogue, or its per-organisation entitlement reasoning - only what the filter panel
> adds: counts, value lists, and which filters are offered. Read discover's §2, §3, §5 and §7
> first; this document cross-references them by test-case number rather than restating them.
>
> **Product ids differ between environments** (discover's product-id warning applies here too, but
> this endpoint never returns one). **No expected value in this document is written in terms of an
> id.**

## Contents

- [1. What the API does](#1-what-the-api-does)
- [2. What governs it](#2-what-governs-it)
- [3. The request](#3-the-request)
- [4. The response](#4-the-response)
  - [`totalElements`](#totalelements)
  - [`textSearch`](#textsearch)
  - [`filters[]`](#filters)
  - [`key`, `target`, `label`, `dataType`, `multiValued`, `operators`, `defaultOperator`, `sortable`](#key-target-label-datatype-multivalued-operators-defaultoperator-sortable)
  - [`values[]`](#values)
  - [`missingCount`](#missingcount)
  - [`truncated`](#truncated)
  - [`policy`](#policy)
  - [The counting rule](#the-counting-rule)
- [5. Critical fields](#5-critical-fields)
- [6. Test data](#6-test-data)
- [7. Shared preconditions](#7-shared-preconditions)
- [8. Test cases](#8-test-cases)
  - [A. Baseline and response shape (TC-01 – TC-04)](#a-baseline-and-response-shape)
  - [B. Narrowing and the counting rule (TC-05 – TC-08)](#b-narrowing-and-the-counting-rule)
  - [C. Masking and permission (TC-09 – TC-10)](#c-masking-and-permission)
  - [D. `isOwnProduct` (TC-11 – TC-12)](#d-isownproduct)
  - [E. Free text (TC-13)](#e-free-text)
  - [F. Refusals shared with discover (TC-14 – TC-16)](#f-refusals-shared-with-discover)
  - [G. Policy switched off (TC-17 – TC-18)](#g-policy-switched-off)
- [9. Edge cases](#9-edge-cases)
- [10. Defect triage](#10-defect-triage)

---

## 1. What the API does

`POST /api/v1/product/discover/filter` returns the product catalogue's **filter panel** for one
search: every filter the caller may apply, what it is, how it compares, and - where listing them
is worth it - the distinct values it holds with how many products carry each one, for *this*
request.

It exists so a UI never has to guess the vocabulary or pay for it one value at a time. Before this
endpoint, the catalogue UI hard-coded every field, attribute and value list
(`ui/src/vocabulary.js`) and fired one `discover` call per value per group to get a count - 13
requests per refresh, and only for the four groups someone had written down. This endpoint gives
the whole panel in one request, built from the same policy decision and the same search criteria
as discover, so the panel can never offer or count more than discover would return.

Three things follow from "built from the same decision as discover":

| Question | Answered the same way discover answers it |
|---|---|
| May this caller search at all? | the `product_discovery` role, then the policy's caller gates |
| Which products exist for this caller? | the policy's `row_filter`; this is what every count is computed under |
| What may be asked, and what is shown? | the policy's allowed / masked lists decide which filters appear at all |

**The filter *permission* decides what appears here, not result *visibility*.** An attribute the
contract lets the caller filter on is listed with its values and counts even when policy hides
that block from the caller's own discover *results* - BCC (`OFFICIAL`) sees `quality_designation`
and `record_unit` values in its panel although its own products' `policyAttributes` block is masked
in discover's response. This discloses nothing discover does not already answer through
`totalElements`: BCC could already learn the same counts by sending discover once per value. The
panel answers it in one request instead. See [TC-09](#tc-09-bcc-sees-quality_designation-and-record_unit-but-no-subscribedby).

## 2. What governs it

The same four gates as discover, with one difference in how the fourth is resolved:

| # | Gate | Refusal |
|---|---|---|
| 1 | Authentication: a valid bearer token | `401` |
| 2 | Authorisation: the `product_discovery` role | `403 "Access denied: insufficient permissions for this operation"` |
| 3 | Request binding and bean validation (`@Valid` on the body) | `400` |
| 4 | Policy: `product.discover_filter` → **routed** to `policies.product.discover` | `403 "Access denied by policy"` with `reasons` |
| 5 | Criteria validation inside the handler | `400 "Invalid search criteria: …"` |

The handler is annotated `@Policy(resource = "product", action = "discover_filter", details =
ProductDiscoveryPolicyDecisionDetails.class)`. There is no `policies.product.discover_filter`
module; an entry in `docker/opa/policies/routing/data.json` sends the action to
`policies.product.discover` instead:

```json
{"id": "route-product-discover-filter", "resource": "product", "action": "discover_filter",
 "policy": "product.discover", "priority": 10, "enabled": true}
```

This matters for two things a tester should expect:

- **Provenance differs from discover's even though the rule is the same.** `policy.resolution` is
  `route` here, `exact` on discover, and `policy.id` is `product.discover` on both. A log line can
  therefore tell the two endpoints apart even though one rule answers both.
- **A caller gets the identical allow/deny verdict and the identical contract** `POST
  /api/v1/product/discover` would give the same token the same body, because it is the same rule
  seeing the same input, module for module. `dispatch_test.rego`'s
  `test_discover_filter_is_routed_to_the_discover_rule` asserts exactly this at build time.

Everything else in discover's §2 applies unchanged: the calling organisation comes from the
token's `organisation` claim, never the body; OPA fails closed.

## 3. The request

**Identical to discover's**: `ProductDiscoveryRequestDTO`, the same `text`, `filters`, `sort`,
`page` and `size` members, the same limits, the same operators, the same default-operator rules.
See [discover §3](product-discover.md#3-the-request) for the full table.

Two members are accepted but have no effect here:

| Member | Effect on discover | Effect on discover/filter |
|---|---|---|
| `text` | the free-text condition | drives counts (narrows every group as a request filter would) and enables/disables `textSearch` |
| `filters` | the `WHERE` clause | drives counts, each excluded from its own target's counts (see [counting rule](#the-counting-rule)) |
| `sort` | the `ORDER BY` | **accepted, validated identically, then ignored** - there is no page to order |
| `page`, `size` | paging | **accepted, validated identically, then ignored** - there is no page to return |

`sort`, `page` and `size` are still validated exactly as discover validates them (an invalid
`direction` is still a `400`), because validation and the policy decision happen before the
service knows which members it will use. See [TC-16](#tc-16-sort-page-and-size-are-accepted-validated-and-ignored).

## 4. The response

```json
{
  "totalElements": 6,
  "textSearch": { "enabled": true, "fields": ["name", "displayName", "description", "longDescription"] },
  "filters": [
    {
      "key": "field:type",
      "target": { "field": "type" },
      "label": "Type",
      "dataType": "TEXT",
      "multiValued": false,
      "operators": ["eq", "neq", "in", "not_in", "any_of", "none_of", "contains", "exists", "not_exists"],
      "defaultOperator": "in",
      "sortable": true,
      "values": [
        { "value": "file", "count": 3, "selected": false },
        { "value": "topic", "count": 3, "selected": false }
      ]
    },
    {
      "key": "field:format",
      "target": { "field": "format" },
      "label": "Format",
      "dataType": "TEXT",
      "multiValued": false,
      "operators": ["eq", "neq", "in", "not_in", "any_of", "none_of", "contains", "exists", "not_exists"],
      "defaultOperator": "in",
      "sortable": true,
      "values": [
        { "value": "CSV", "count": 1, "selected": false },
        { "value": "GeoJSON", "count": 1, "selected": false },
        { "value": "JSON", "count": 1, "selected": false },
        { "value": "NetCDF", "count": 1, "selected": false },
        { "value": "XML", "count": 1, "selected": false },
        { "value": "Avro", "count": 0, "selected": false },
        { "value": "RDF", "count": 0, "selected": false }
      ],
      "missingCount": 1
    },
    {
      "key": "field:organisation.key",
      "target": { "field": "organisation.key" },
      "label": "Organisation",
      "dataType": "TEXT",
      "multiValued": false,
      "operators": ["eq", "neq", "in", "not_in", "any_of", "none_of", "contains", "exists", "not_exists"],
      "defaultOperator": "eq",
      "sortable": true,
      "values": [
        { "value": "ENV", "count": 6, "selected": true },
        { "value": "BCC", "count": 5, "selected": false },
        { "value": "HEG", "count": 4, "selected": false }
      ]
    }
  ],
  "policy": { "...": "the policy block discover returns, unchanged; absent when policy is switched off" }
}
```

This is a real response: ENV, posting `{"filters":[{"field":"organisation.key","values":["ENV"]}]}`
(`filters[]` abbreviated to three of its twelve entries for ENV; see [§6](#6-test-data) and
[TC-07](#tc-07-choosing-organisationkey--env-narrows-format-and-type-while-organisation-stays-put)
for the full derivation).

### `totalElements`

How many products match the **whole** request - `text`, every `filters[]` entry, under the
caller's row filter. Exactly what `page.totalElements` of `POST /api/v1/product/discover` would
report for the identical body and token. See [TC-04](#tc-04-totalelements-equals-discovers-totalelements-for-the-same-body).

### `textSearch`

```json
{ "enabled": true, "fields": ["name", "displayName", "description", "longDescription"] }
```

- `enabled`: whether the caller may send `text` at all - `contract.textSearchFields()` non-empty.
- `fields`: the fields a term is matched against, same list, same order, as discover's
  `policy.textSearchFields`.

`fields` is `[]` and `enabled` is `false` only for a caller whose policy masks every text-searchable
field - the same condition discover's [TC-35](product-discover.md#tc-35-textnot_permitted) covers,
which no shipped organisation triggers without editing the policy.

### `filters[]`

One entry per filter the caller may use, **in this fixed order**: fields in `ProductField`
declaration order (never `id` - see the note under [§5](#5-critical-fields)), then product
attributes sorted by label (case-insensitive), then organisation attributes sorted by label. A
filter the contract does not permit (masked, or not in the allowed list) is not in the array at
all - there is no "disabled" entry.

### `key`, `target`, `label`, `dataType`, `multiValued`, `operators`, `defaultOperator`, `sortable`

These eight describe the filter itself, independent of the request; two callers with the same
entitlement get byte-identical values for all eight, whatever criteria either one sent.

| Member | Meaning | Field example | Attribute example |
|---|---|---|---|
| `key` | stable id for UI state | `field:format` | `attribute:product.quality_designation` |
| `target` | copied verbatim into a discover `filters[]` entry | `{"field": "format"}` | `{"scope": "product", "attribute": "quality_designation"}` |
| `label` | caller-facing name | `"Format"`, a fixed label per field (§5.1) | `policy_attribute_definition.display_name`, e.g. `"Quality designation"`; falls back to the bare name when none is set |
| `dataType` | `TEXT`, `NUMBER` or `BOOLEAN` | `ProductField.Kind` | the definition's `data_type`, mapped (`STRING`→`TEXT`; no `NUMBER` or `BOOLEAN` attribute exists in the sample data) |
| `multiValued` | can the target carry more than one value at once | always `false`, **except `subscribedBy`** | the definition's `multi_valued` flag |
| `operators` | comparisons this target accepts, by wire name | the field operator set (§5.1) minus whatever discover would refuse | the attribute operator set (§5.2), `all_of` added when multi-valued, ordering operators added when `dataType` is `NUMBER` |
| `defaultOperator` | what the UI should send for a value pick | `contains` for `name`/`displayName`/`description`/`longDescription`; `eq` for the boolean `isOwnProduct`; `any_of` for the multi-valued `subscribedBy`; `in` for every other listing field | `eq` for a boolean attribute (none exist); `any_of` when multi-valued; `in` otherwise |
| `sortable` | may the caller sort on this target in `POST /api/v1/product/discover` | `ProductField.isSortable()` - `false` for `description`, `longDescription` and `subscribedBy` | always `true` |

`operators` **must never offer something discover would refuse.** `subscribedBy`'s set has no
`contains` (discover never accepts it there); every multi-valued attribute's set gains `all_of`;
a `NUMBER` attribute's set gains `lt`/`lte`/`gt`/`gte`. See [§5](#5-critical-fields) for the
complete per-target tables.

### `values[]`

Present on every entry the panel offers. The four prose fields that would otherwise carry no
`values` (`name`, `displayName`, `description`, `longDescription`) are not offered in the panel at all (see the
[§5.1 note](#51-product-fields)), and `topic` and `source` are excluded for the same reason, so no
entry in `filters[]` lacks `values` any more. Each entry:

```json
{ "value": "CSV", "count": 1, "selected": false }
```

- `value`: a string, or a JSON boolean for `isOwnProduct`. Reported **as stored**
  (`"CSV"`, not `"csv"`).
- `count`: how many products satisfy the [counting rule](#the-counting-rule) for this value.
- `selected`: `true` when the caller's own request already has an `eq`, `in` or `any_of` filter on
  this exact target naming this value, compared the way discover matches that filter:
  **case-insensitively for a field** (discover compares fields with `LOWER()`, so `csv` selects
  `CSV`) and **exactly for an attribute** (discover matches attributes with `a.item IN (...)`, so
  `Anonymised` does not select `anonymised`). A `neq`, `contains`, `exists`
  or any other operator narrows the other groups but never marks a value `selected` - the request
  did not *pick* that value, it excluded or tested for others.

Which values are listed is decided **once, by the row filter alone** - ignoring the request's own
`filters` and `text` entirely: the distinct values held by at least one product the caller can see
at all. Narrowing the request narrows *counts*, never the *set of rows shown*; see
[TC-07](#tc-07-choosing-organisationkey--env-narrows-format-and-type-while-organisation-stays-put).
A value no visible product holds is never listed, even when a `policy_attribute_definition`
declares it in `allowed_values` - `rare_condition_cohorts` is a declared value of
`population_risk_tags` that no sample product carries, and it never appears; see
[§6](#6-test-data).

**Ordering**: count descending, then value ascending case-insensitively; capped at 50 (§[`truncated`](#truncated)).
`isOwnProduct` is the one exception: always exactly `true` then `false`, in that order, whatever
their counts, because there are only ever two.

**Case merging applies to fields, never to attributes.** `format` values compare
case-insensitively in the database (`LOWER(p.format) IN (...)`), so `CSV` and `csv` would be one
row at search time; the panel merges them the same way, summing their counts under the spelling
with the most products (ties broken by the lexicographically smaller spelling). An attribute
filter compares exactly (`a.item IN (...)`), so `validated` and `Validated` would be two different
rows to discover, and the panel lists them as two distinct values too - never merged. The sample
data happens to be consistently cased, so this only shows up if a row is edited to differ; it is
exercised directly in `FacetValuesTest`.

### `missingCount`

How many products in the **narrowed** set (the whole request, minus this target's own filters -
see the counting rule) have no value at all for this target: what a `not_exists` filter on it
would match. Present only alongside `values`, and omitted - not `0` - when it would be zero,
matching the rest of the API's "absent, never a zero default" convention.

### `truncated`

`true`, and present, only when more than 50 distinct values existed for this target and the list
was cut to the 50 most-matched. Omitted - not `false` - otherwise. No shipped sample attribute or
field has more than a handful of distinct values, so every example in this document is untruncated;
exercising `truncated: true` needs seeded data with 51+ distinct values for one target (see
`FacetValuesTest` for the unit-level proof the cap and flag are applied).

### `policy`

The identical object `POST /api/v1/product/discover` returns in its own `policy` member - same
builder, same contract (`ProductDiscoveryServiceImpl.policyBlock`). Present only when policy
enforcement is on; absent (not `null`) when `application.opa.enabled=false`. See discover's
[§4 "The `policy` block"](product-discover.md#the-policy-block) and
[TC-17](#tc-17-with-policy-off-the-panel-is-wide-open-and-sensitive-attributes-appear).

### The counting rule

This is standard **disjunctive faceting**, stated once in the design and reproduced here because
every test case in §8.B depends on it:

> A value's `count` is the number of products that satisfy the policy's row filter, the request's
> `text`, and every request filter **except those on this same target**, and that hold the value.

Two things fall out of that sentence that are easy to get backwards:

1. **A target's own filter never narrows its own counts.** Choosing `organisation.key = ENV`
   narrows every *other* group (`format`, `type`, every attribute) to ENV's products, but the
   **Organisation** group keeps showing what BCC's and HEG's counts would be if the caller switched
   - that is the whole point of a facet panel: it tells you what picking something else would give
   you, not just what you already picked.
2. **Every other filter and the text term still apply**, including filters on *other* targets.
   Several filters on one target are all excluded together from that target's own counts (picking
   two values of `type` does not stop the Type group reporting what a *third* value of `type` would
   give).

**Worked example**, ENV posting `{"filters":[{"field":"organisation.key","values":["ENV"]}]}`
(the response shown in [§4](#4-the-response) above):

| Group | Own filter excluded from its own count? | Narrowed by the `organisation.key=ENV` filter? | Result |
|---|---|---|---|
| Organisation | yes - it *is* this group | no | unchanged from the baseline `{}` panel: `ENV:6, BCC:5, HEG:4` |
| Format | n/a - no filter on `format` | yes | ENV's 15-product baseline (`CSV:4, JSON:4, Avro:1, GeoJSON:1, NetCDF:1, RDF:1, XML:1`, `missingCount:2`) narrows to ENV's own 6: `CSV:1, GeoJSON:1, JSON:1, NetCDF:1, XML:1, Avro:0, RDF:0`, `missingCount:1` |
| Type | n/a | yes | baseline `topic:8, file:6`, `missingCount:1` narrows to `topic:3, file:3`, `missingCount:0` |
| `totalElements` | n/a | yes | 15 → **6** |

`RDF` and `Avro` staying in the list at `count: 0` is the value-listing rule in action: both are
values held by a product ENV can *see* but does not *own*
(`ScotlandLandRegisterExtract` and `HelpToBuyApplicants`, both HEG's), so they are listed, even
though no product in the *narrowed* set (ENV's own 6) holds either. The UI greys them out rather
than removing them, so switching the organisation filter back off does not make the group's rows
jump around.

## 5. Critical fields

Everything here mirrors discover's [§5](product-discover.md#5-critical-fields-for-filtering)
exactly - the panel offers nothing discover would refuse and withholds nothing discover would
accept - with the operator, label and default-operator mechanics this endpoint adds on top.

### 5.1 Product fields

Only six of the twelve filterable product fields are ever offered in the panel at all; the other
six are filterable on `POST /api/v1/product/discover` exactly as before, but
`FilterOptionTargets.isOfferedInPanel` excludes them from `filters[]` unconditionally, before the
contract is consulted - so even a caller whose contract would permit them never sees them here.
Being left out this way is a third reason an entry can be absent, alongside "not in the caller's
allowed list" and "masked", and unlike those two it does not depend on the caller at all.

| API name | `dataType` | `multiValued` | `operators` | `defaultOperator` | offered in panel? | lists `values`? | `label` |
|---|---|---|---|---|---|---|---|
| `id` | — | — | — | — | **never** (policy or no, see note below) | — | — |
| `name` | TEXT | false | 9: eq, neq, in, not_in, any_of, none_of, contains, exists, not_exists | `contains` | **never** - prose, already searched by `textSearch` | — | — |
| `displayName` | TEXT | false | same 9 | `contains` | **never** - prose, already searched by `textSearch` | — | — |
| `description` | TEXT | false | same 9 | `contains` | **never** - prose, already searched by `textSearch` | — | — |
| `longDescription` | TEXT | false | same 9 | `contains` | **never** - prose, already searched by `textSearch` | — | — |
| `topic` | TEXT | false | same 9 | `in` | **never** - an identifier nobody browses by | — | — |
| `source` | TEXT | false | same 9 | `in` | **never** - an identifier nobody browses by | — | — |
| `type` | TEXT | false | same 9 | `in` | when permitted | yes | Type |
| `format` | TEXT | false | same 9 | `in` | when permitted | yes | Format |
| `organisation.key` | TEXT | false | same 9 | `eq` | when permitted | yes | Organisation |
| `organisation.name` | TEXT | false | same 9 | `eq` | when permitted | yes | Organisation name |
| `subscribedBy` | TEXT | **true** | 8: eq, neq, in, not_in, any_of, none_of, exists, not_exists (**no `contains`**) | `any_of` | when permitted | yes | Subscribed by |
| `isOwnProduct` | BOOLEAN | false | 8: eq, neq, in, not_in, any_of, none_of, exists, not_exists | `eq` | when permitted and the caller's organisation is known | yes, always both `true`/`false` | My organisation's products |

> **`id` is never a filter option, whether or not policy is enforced.** `FilterOptionTargets`
> excludes `ProductField.ID` unconditionally, before consulting the contract at all. This is
> *stricter* than "allowed − masked": with policy **switched off**, discover's own open contract
> would in fact accept a filter on `id` (`contract.mayFilterOn` returns `true` for every target when
> enforcement is off), but the filter panel still never lists it as an option in that state. This
> is a deliberate implementation choice beyond what the design note ("`id` never appears while
> policy is enforced") states outright - see the report accompanying this document.

> **`name`, `displayName`, `description`, `longDescription`, `topic` and `source` are never filter options either, for
> the same unconditional reason as `id`.** `FilterOptionTargets.isOfferedInPanel` leaves them out
> before the contract is consulted, whether or not the caller's contract would otherwise permit
> them: the first four are prose already covered by the free-text box (`textSearch`), and the last
> two are identifiers nobody browses a value list for. **Discover itself is unchanged**: a caller
> who names one of these six directly in `POST /api/v1/product/discover`'s `filters[]` is answered
> exactly as before, and each still appears in `policy.filterableFields` - only the panel stops
> offering it. See [TC-02](#tc-02-the-six-text-searched-and-identifier-fields-are-permitted-but-not-offered).

Because the six remaining fields all list values when they are offered at all, **every entry
`filters[]` now carries, `values` is present on every one of them** - there is no field entry
without `values` any more. (An attribute entry always listed `values` already; see §5.2.)

### 5.2 Policy attributes

| Attribute | Scope | `dataType` | `multiValued` | `operators` | `defaultOperator` | `label` (from `display_name`) |
|---|---|---|---|---|---|---|
| `record_unit` | product | TEXT | no | 9 (as `topic` above) | `in` | Record unit |
| `identifiability` | product | TEXT | no | 9 | `in` | Identifiability |
| `quality_designation` | product | TEXT | no | 9 | `in` | Quality designation |
| `temporal_resolution` | product | TEXT | no | 9 | `in` | Temporal resolution |
| `population_risk_tags` | product | TEXT | **yes** | 10 (9 + `all_of`) | `any_of` | Population risk tags |
| `coverage_jurisdictions` | product | TEXT | **yes** | 10 | `any_of` | Coverage jurisdictions |
| `jurisdictions` | organisation | TEXT | **yes** | 10 | `any_of` | Jurisdictions |
| `responsibility_areas` | organisation | TEXT | **yes** | 10 | `any_of` | Responsibility areas |
| `authorised_classifications` | organisation | TEXT | **yes** | 10 | `any_of` | Authorised classifications |
| `permitted_purposes` | organisation | TEXT | **yes** | 10 | `any_of` | Permitted purposes |
| `department_id` | organisation | TEXT | no | 9 | `in` | Department id |
| `nationality` | organisation | TEXT | no | 9 | `in` | Nationality |

No sample-data attribute is `NUMBER` or `BOOLEAN`, so no attribute in the shipped catalogue carries
an ordering operator (`lt`/`lte`/`gt`/`gte`) or the boolean operator set; both are exercised at the
unit level only (`FilterOptionTargetsTest`).

**None of these twelve is automatically a filter option.** Whether one appears in a given caller's
`filters[]` is decided the same three ways discover decides `policy.filterableAttributes`:

| Reason an attribute is absent from the panel | Example, enforced |
|---|---|
| not in the caller's `allowed_filtered_attributes` | `coverage_jurisdictions`, `department_id`, `nationality` - **absent for every organisation**, none permits them |
| masked for this caller (`allowed − masked`) | n/a for attributes in the sample policy - no organisation's allowed list and masked list overlap today, unlike fields (`source` for BCC) |
| flagged `sensitive` in the catalogue, while `mask_sensitive_attributes` is on | `population_risk_tags` for HEG and BCC (below SECRET); `authorised_classifications` and `permitted_purposes` are sensitive too, but neither is in **any** organisation's allowed list regardless |

`organisation.jurisdictions` and `organisation.responsibility_areas` are the only attributes every
sample organisation may filter on; see [§6](#6-test-data) for the per-organisation panel.

### 5.3 Which filters appear, and in what order

Reproduced from the design, ground-truthed against `FilterOptionTargets.permitted`:

1. `ProductField` members that are filterable, offered in the panel (`isOfferedInPanel` - never
   `id`, `name`, `displayName`, `description`, `longDescription`, `topic` or `source`, §5.1), **and** permitted by the
   contract, in declaration order.
2. Product-scoped attributes permitted by the contract, sorted by `label` (case-insensitive).
3. Organisation-scoped attributes permitted by the contract, sorted by `label` (case-insensitive).

## 6. Test data

The catalogue, the three organisations, their clearances and purposes, and every attribute value
are exactly discover's - reproduced in full in
[`product-discover.md` §6](product-discover.md#6-test-data). This section adds only what that one
does not need: product `format`, and the panel each organisation is given.

### 6.1 `format`, by product

Set by `src/main/resources/db/samples/V20261018121000__add_sample_product_display_names_and_formats.sql`
(the same migration TC-47/TC-48 of discover's spec exercise through `displayName`).
`UnclassifiedDraftDataset` and `Bristol_Cycle%Counts` have neither a `displayName` nor a `format`.

| Product | Owner | `format` |
|---|---|---|
| FloodRiskMapZones | ENV | GeoJSON |
| RiverLevelTelemetry | ENV | JSON |
| CoastalErosionForecast | ENV | NetCDF |
| PollutionIncidentReports | ENV | XML |
| WaterAbstractionLicences | ENV | CSV |
| UnclassifiedDraftDataset | ENV | *(none)* |
| BrownfieldLandAvailability | HEG | CSV |
| AffordableHousingCompletions | HEG | CSV |
| HelpToBuyApplicants | HEG | Avro |
| HousingNeedSurvey | HEG | Parquet |
| EmptyHomesRegister | HEG | XML |
| ScotlandLandRegisterExtract | HEG | RDF |
| PendingPlanningApplications | BCC | JSON |
| BristolAirQualitySensors | BCC | JSON |
| BristolSchoolPlaces | BCC | CSV |
| BristolTemporaryAccommodation | BCC | JSON |
| `Bristol_Cycle%Counts` | BCC | *(none)* |

> **Environment check before trusting any `format` number in this document:** this migration and
> its schema counterpart (`V20261018120000__add_product_display_name_and_format.sql`) are the two
> newest in `src/main/resources/db/migration` and `db/samples`. If
> `SELECT format FROM mn.product LIMIT 1` errors with "column does not exist", or every `format` is
> `NULL`, the environment has not applied them yet; apply migrations before running §8's Format
> cases, or treat every count derived from this table as unobserved until you have.

### 6.2 The panel per organisation (empty body, policy on)

Derived from discover's §5.4 allowed/masked lists, which this endpoint shares exactly, narrowed
further by the panel's own unconditional exclusions: `id`, `name`, `displayName`, `description`,
`longDescription`, `topic` and `source` never count here, per §5.1, whatever the contract would permit. Of the six
fields the panel can ever offer (`type`, `format`, `organisation.key`, `organisation.name`,
`subscribedBy`, `isOwnProduct`), only `subscribedBy` is masked for any sample organisation (BCC).

| | **ENV** | **HEG** | **BCC** |
|---|---|---|---|
| field filters offered | 6: Type, Format, Organisation, Organisation name, Subscribed by, My organisation's products (every panel-eligible field) | 6 (same as ENV - `consumers` has no field name to exclude) | 5: Type, Format, Organisation, Organisation name, My organisation's products (`subscribedBy` absent - masked) |
| product-attribute filters offered | 4: Identifiability, Population risk tags, Quality designation, Record unit | 4: Identifiability, Quality designation, Record unit, Temporal resolution | 2: Quality designation, Record unit |
| organisation-attribute filters offered | 2: Jurisdictions, Responsibility areas | 2: Jurisdictions, Responsibility areas | 2: Jurisdictions, Responsibility areas |
| **total `filters[]` entries** | **12** | **12** | **9** |
| `totalElements` (`{}`) | 15 | 12 | 8 |
| `textSearch.enabled` | true | true | true |

ENV and HEG each lose six entries from the pre-panel-narrowing count of 18 (`name`, `displayName`,
`description`, `longDescription`, `topic`, `source`); BCC loses five from its 14 (`source` was already
masked for BCC, so only `name`, `displayName`, `description`, `longDescription` and `topic` are newly
dropped from its field count).

**`Type` group, baseline `{}`** (used throughout §8): ENV `topic:8, file:6, missingCount:1`; the
full per-caller breakdown follows directly from discover's §6.1 product list and is left as an
exercise identical to TC-08 of discover's spec, with `in` replaced by "every value, with its
count".

**`Format` group, baseline `{}`, ENV**: `CSV:4, JSON:4, Avro:1, GeoJSON:1, NetCDF:1, RDF:1, XML:1,
missingCount:2` (derived from §6.1 above against ENV's visible 15 products: `CSV` -
WaterAbstractionLicences, BrownfieldLandAvailability, AffordableHousingCompletions,
BristolSchoolPlaces; `JSON` - RiverLevelTelemetry, PendingPlanningApplications,
BristolAirQualitySensors, BristolTemporaryAccommodation; `Avro` - HelpToBuyApplicants; `GeoJSON` -
FloodRiskMapZones; `NetCDF` - CoastalErosionForecast; `RDF` - ScotlandLandRegisterExtract; `XML` -
PollutionIncidentReports; missing - UnclassifiedDraftDataset, `Bristol_Cycle%Counts`).

**`record_unit` and `quality_designation`, baseline `{}`, BCC** (BCC's visible 8: its own 5 plus
FloodRiskMapZones, RiverLevelTelemetry, AffordableHousingCompletions):

| Attribute | Values and counts | `missingCount` |
|---|---|---|
| `quality_designation` | `validated:6, provisional:2` | 0 |
| `record_unit` | `geographic_area:4, household:2, person:1, property:1` | 0 |

**`organisation.jurisdictions`, baseline `{}`, ENV** (multi-valued, so each visible product counts
under every jurisdiction its *owning organisation* holds - ENV: England, Wales; HEG: England,
Scotland; BCC: England, Bristol):

`England:15, Wales:6, Bristol:5, Scotland:4`, `missingCount:0` - every organisation has at least one
jurisdiction recorded.

**`population_risk_tags`, policy switched off, every caller, baseline `{}`** (every product
visible, no row filter; tags from discover's §6 test-data table):

`children:2` (HelpToBuyApplicants, BristolSchoolPlaces), `domestic_abuse_survivors:2`
(PendingPlanningApplications, BristolTemporaryAccommodation), `protected_witnesses:1`
(PendingPlanningApplications); `rare_condition_cohorts` **absent** - a declared `allowed_values`
entry no sample product carries, so the value-listing rule never surfaces it;
`missingCount:13` (17 products total, 4 carry at least one tag).

## 7. Shared preconditions

Identical to discover's [§7](product-discover.md#7-shared-preconditions), **P1–P5**. Add one
helper alongside its `discover()` and `names()`:

```bash
filters() {   # filters <TOKEN> '<json body>'
  curl -sk --cert client.crt --key client.key \
    -X POST 'https://localhost:8090/api/v1/product/discover/filter' \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "${2:-\{\}}"
}
values_of() { jq --arg k "$1" '.filters[] | select(.key==$k) | .values'; }   # values_of <key>
```

---

## 8. Test cases

### A. Baseline and response shape

#### TC-01: An empty body returns the caller's whole panel

**Objective.** Confirm the panel reflects the caller's catalogue size and that the three
organisations differ, exactly as discover's TC-01.

**Preconditions.** P1–P4.

**Test data.** `{}` as each of the three tokens.

**Given** the sample catalogue
**When** an empty body is posted as ENV, then HEG, then BCC
**Then** each response is `200` with `totalElements` 15, 12, 8 respectively (§6.2)
**And** the `filters` array has 12, 12, 9 entries respectively (§6.2).

```bash
for t in "$ENV_TOKEN" "$HEG_TOKEN" "$BCC_TOKEN"; do
  filters "$t" '{}' | jq '{total: .totalElements, groups: (.filters | length)}'
done
```

*Why:* identical numbers for all three callers mean either the same token was sent three times, or
the panel is not built from the per-caller contract at all - the same tell discover's TC-01 uses.

---

#### TC-02: The six text-searched and identifier fields are permitted but not offered

**Objective.** Confirm `name`, `displayName`, `description`, `longDescription`, `topic` and `source` are left out of
`filters[]` unconditionally - even for ENV, which permits filtering on every one of them - while
`POST /api/v1/product/discover` still accepts a filter on each and still lists each in its own
`policy.filterableFields`. This also establishes, for every entry that *is* offered, that `values`
is never absent: there is no longer a "non-listing" shape to contrast it with.

**Preconditions.** P1–P4.

**Test data.** `{}` as ENV, to both `/discover` and `/discover/filter`.

**Given** ENV's contract permits `name`, `displayName`, `description`, `longDescription`, `topic` and `source` as
discover filters (discover's §5.4: ENV has no masked fields at all)
**When** ENV's filter panel is read
**Then** `filters[]` contains **no** entry whose `key` is `field:name`, `field:displayName`,
`field:description`, `field:longDescription`, `field:topic` or `field:source`
**And** the identical token's `POST /api/v1/product/discover` response still lists `name`,
`displayName`, `description`, `longDescription`, `topic` and `source` in `policy.filterableFields`
**And** every entry that *is* in `filters[]` carries a `values` member - there is no entry with
`key, target, label, dataType, multiValued, operators, defaultOperator, sortable` and nothing else.

```bash
filters "$ENV_TOKEN" '{}' \
  | jq '[.filters[].key] | any(. == "field:name" or . == "field:displayName" or . == "field:description" or . == "field:longDescription" or . == "field:topic" or . == "field:source")'
# false
discover "$ENV_TOKEN" '{}' | jq '.policy.filterableFields | sort'
# contains "description", "displayName", "longDescription", "name", "source", "topic" alongside the rest
filters "$ENV_TOKEN" '{}' | jq 'all(.filters[]; has("values"))'
# true
```

*Why:* this is the behaviour change itself - a field the contract permits can still be withheld
from the panel for a reason that has nothing to do with the caller or policy, namely that the panel
is not the right surface for it (prose already has the free-text box; an identifier has no list
anyone browses). Checking `policy.filterableFields` on the sibling endpoint is what proves the gap
is deliberate narrowing by `FilterOptionTargets`, not a masking regression that would also make
discover refuse the field.

---

#### TC-03: `target` round-trips into a discover filter, for a field and for an attribute

**Objective.** Confirm `target` is exactly what a caller would paste into `POST
/api/v1/product/discover`'s `filters[]`.

**Preconditions.** P1–P4.

**Test data.** `{}` as ENV; inspect `field:organisation.key` and
`attribute:organisation.jurisdictions`.

**Given** ENV's panel
**When** both entries' `target` are read
**Then** `field:organisation.key` carries `{"field": "organisation.key"}`
**And** `attribute:organisation.jurisdictions` carries
`{"scope": "organisation", "attribute": "jurisdictions"}`
**And** posting either verbatim as a discover `filters[]` entry (adding `"values":[...]`) is
accepted, never a `403 filter.field_not_permitted` or `filter.attribute_not_permitted`.

```bash
filters "$ENV_TOKEN" '{}' | jq '.filters[] | select(.key|test("organisation")) | {key, target}'
```

*Why:* `target` exists so the UI never has to re-derive the `scope`/`field`/`attribute` split the
caller's criteria needs; if it round-tripped to a `403`, the panel would be describing a filter
discover does not recognise as the same one.

---

#### TC-04: `totalElements` equals discover's `totalElements` for the same body

**Objective.** Confirm the two endpoints agree on the headline count.

**Preconditions.** P1–P4.

**Test data.** `{"filters":[{"field":"type","values":["file"]}]}` as ENV (discover's
[TC-08](product-discover.md#tc-08-filter-on-a-product-field-type) gives **6** for this body).

**Given** the identical body and token
**When** it is posted to `/discover` and to `/discover/filter`
**Then** `discover`'s `page.totalElements` and `discover/filter`'s `totalElements` are both **6**.

```bash
d=$(discover "$ENV_TOKEN" '{"filters":[{"field":"type","values":["file"]}]}' | jq '.page.totalElements')
f=$(filters  "$ENV_TOKEN" '{"filters":[{"field":"type","values":["file"]}]}' | jq '.totalElements')
[ "$d" = "$f" ] && echo MATCH
```

Repeat with any other body from discover's §8.C/D and the two counts never diverge - that is the
whole guarantee of sharing one query plan (`ProductQueryPlanner.compile`) and one `count` call.

*Why:* the two endpoints are explicitly built not to have two sources of truth for how many
products match. A divergence here is a defect in the shared planner, not in either endpoint alone.

---

### B. Narrowing and the counting rule

#### TC-05: Each field and attribute group's values are the caller's **whole** visible set

**Objective.** Confirm which values are *listed* is independent of the request's own filters.

**Preconditions.** P1–P4.

**Test data.** `{}` and `{"filters":[{"field":"type","values":["topic"]}]}`, both as ENV.

**Given** ENV's 15 visible products, of which 6 have `type=topic`
**When** the `format` group is read from both responses
**Then** both list the **same seven** distinct `format` values (`Avro, CSV, GeoJSON, JSON,
NetCDF, RDF, XML`) - the second response's counts are lower, but **no value is added or removed**.

```bash
diff <(filters "$ENV_TOKEN" '{}' | values_of field:format | jq '[.[].value] | sort') \
     <(filters "$ENV_TOKEN" '{"filters":[{"field":"type","values":["topic"]}]}' \
         | values_of field:format | jq '[.[].value] | sort')
# no output: identical value sets
```

*Why:* this is the rule the whole panel depends on - "values" answers *what exists*, "counts"
answer *how many, given what you've already picked*. If filtering changed which rows appear, a
user who narrows their search would watch filter options disappear under them.

---

#### TC-06: Narrowing changes counts and `totalElements` together

**Objective.** Confirm every count in the panel narrows consistently with `totalElements` when a
filter is added.

**Preconditions.** P1–P4.

**Test data.** `{}`, then `{"filters":[{"attribute":"record_unit","values":["geographic_area"]}]}`,
both as ENV (discover's [TC-16](product-discover.md#tc-16-filter-on-an-attribute-every-caller-may-use-record_unit)
gives **6** for the narrowed body).

**Given** ENV's baseline Type group `topic:8, file:6, missingCount:1`
**When** the `record_unit = geographic_area` filter is added
**Then** `totalElements` drops from 15 to **6**
**And** the Type group narrows to the six `geographic_area` products: FloodRiskMapZones,
RiverLevelTelemetry, CoastalErosionForecast, UnclassifiedDraftDataset, BristolAirQualitySensors,
`Bristol_Cycle%Counts` → `topic:3` (FloodRiskMapZones, RiverLevelTelemetry, BristolAirQualitySensors),
`file:2` (CoastalErosionForecast, UnclassifiedDraftDataset), `missingCount:1`
(`Bristol_Cycle%Counts`).

```bash
filters "$ENV_TOKEN" '{"filters":[{"attribute":"record_unit","values":["geographic_area"]}]}' \
  | jq '{total: .totalElements, type: (.filters[] | select(.key=="field:type") | .values)}'
# {"total":6,"type":[{"value":"file","count":3,"selected":false},{"value":"topic","count":3,"selected":false}]}
```

*Why:* `record_unit` is a different target from `type`, so unlike TC-07 below, *every* group
including Type is narrowed by this filter - there is no exemption for another group's own choice.

---

#### TC-07: Choosing `organisation.key = ENV` narrows Format and Type while Organisation stays put

**Objective.** The signature disjunctive-faceting case: confirm a group's own filter is excluded
from its own counts while narrowing every other group, and that a narrowed-to-zero value is still
listed.

**Preconditions.** P1–P4.

**Test data.** `{"filters":[{"field":"organisation.key","values":["ENV"]}]}` as ENV. This is the
exact response shown in [§4](#4-the-response) and worked through in
[the counting rule](#the-counting-rule).

**Given** ENV's baseline panel (`{}`): Organisation `ENV:6, BCC:5, HEG:4`; Format
`CSV:4, JSON:4, Avro:1, GeoJSON:1, NetCDF:1, RDF:1, XML:1, missingCount:2`; Type `topic:8, file:6,
missingCount:1`; `totalElements:15`
**When** the organisation filter is posted
**Then** `totalElements` becomes **6**
**And** the **Organisation** group is **unchanged**: `ENV:6 (selected:true), BCC:5, HEG:4`
**And** the **Format** group narrows to `CSV:1, GeoJSON:1, JSON:1, NetCDF:1, XML:1, Avro:0, RDF:0,
missingCount:1` - `Avro` and `RDF` stay listed at **zero**, since `HelpToBuyApplicants` and
`ScotlandLandRegisterExtract` (both HEG's) are still products ENV can *see*, just not ones that now
match
**And** the **Type** group narrows to `topic:3, file:3, missingCount:0`.

```bash
filters "$ENV_TOKEN" '{"filters":[{"field":"organisation.key","values":["ENV"]}]}' \
  | jq '{total: .totalElements,
         org: (.filters[] | select(.key=="field:organisation.key") | .values),
         format: (.filters[] | select(.key=="field:format") | .values)}'
```

*Why:* this is the behaviour the whole endpoint exists for - "if I switched to HEG's products
instead, how many would there be?" - and the one a naive implementation gets wrong first, usually
by letting a group's own filter narrow its own counts to a single value with everything else at
zero, or by dropping a zero-count row instead of greying it out.

---

#### TC-08: Several filters on different targets compose, and each is excluded only from its own target

**Objective.** Confirm the exclusion is per-target, not "the most recent filter" or "all filters".

**Preconditions.** P1–P4.

**Test data.**
`{"filters":[{"field":"type","values":["file"]},{"attribute":"quality_designation","values":["validated"]}]}`
as ENV (discover's [TC-14](product-discover.md#tc-14-several-filters-are-and-ed) gives **3** for
this body: WaterAbstractionLicences, AffordableHousingCompletions, ScotlandLandRegisterExtract).

**Given** ENV's baseline Type (`topic:8, file:6`) and Quality designation groups
**When** the two-filter body is posted
**Then** `totalElements` is **3**
**And** the **Type** group is narrowed only by the `quality_designation` filter (its own `type`
filter excluded), so it reports what each type would give *among validated products*, not among
the 3 the whole request matches
**And** symmetrically the **Quality designation** group is narrowed only by the `type=file` filter,
reporting each quality value's count among `file`-typed products: `validated:3, experimental:1,
provisional:1, missingCount:1`
**And** the **Type** group, narrowed only by the `quality_designation=validated` filter, reports
`topic:6, file:3, missingCount:1`
**And** neither group is narrowed by **both** filters at once - that number is `totalElements`, not
a group count.

```bash
filters "$ENV_TOKEN" \
  '{"filters":[{"field":"type","values":["file"]},{"attribute":"quality_designation","values":["validated"]}]}' \
  | jq '{total: .totalElements,
         type: (.filters[] | select(.key=="field:type") | .values),
         quality: (.filters[] | select(.key=="attribute:product.quality_designation") | .values)}'
# totalElements: 3
# type:    topic:6, file:3, missingCount:1   (narrowed by quality_designation=validated only)
# quality: validated:3, experimental:1, provisional:1, missingCount:1   (narrowed by type=file only)
```

*Why:* this is the case that would expose a bug where the "exclude this target's own filter" rule
was implemented as "exclude the most recently added filter" or "exclude nothing" - it needs two
filters on two different targets to catch either mistake.

---

### C. Masking and permission

#### TC-09: BCC sees `quality_designation` and `record_unit`, but no `subscribedBy`

**Objective.** Confirm the filter panel reflects `allowed − masked` exactly as discover's own
`policy.filterableFields`/`filterableAttributes`, for the organisation with the most withheld.
`source` cannot be the field this case checks any more - it is now absent from **every**
organisation's panel unconditionally (§5.1), so it can no longer distinguish masking from the
panel's own narrowing; `subscribedBy` is the one BCC-masked field the panel still ever offers to
anybody, so it carries the whole masking check here.

**Preconditions.** P1–P4.

**Test data.** `{}` as BCC.

**Given** BCC's contract: `source` and `subscribedBy` masked, `quality_designation` and
`record_unit` allowed and unmasked (discover's §5.4)
**When** the panel is read
**Then** `filters[]` contains `attribute:product.quality_designation`
(`validated:6, provisional:2`) and `attribute:product.record_unit`
(`geographic_area:4, household:2, person:1, property:1`) - §6.2
**And** `filters[]` contains **no** entry whose `key` is `field:subscribedBy`
**And** the same request as ENV or HEG **does** carry `field:subscribedBy`
**And** `field:source` is absent for BCC **and** for ENV and HEG alike - that absence is the panel's
unconditional exclusion (§5.1), not masking, and proves nothing about BCC on its own.

```bash
filters "$BCC_TOKEN" '{}' | jq 'any(.filters[]; .key=="field:subscribedBy")'
# false
filters "$ENV_TOKEN" '{}' | jq 'any(.filters[]; .key=="field:subscribedBy")'
# true
```

*Why:* a masked field appearing here with no `values` (so it "looks" withheld) would still be a
disclosure, because the caller could still send it as a discover filter and get a `200` instead of
a `403` if the mechanisms disagreed. There is exactly one way to check this is right: the key is
**absent**, not present-but-empty. With `source` now excluded for everyone, `subscribedBy` is the
only remaining field whose absence can be pinned on BCC's own contract rather than on the panel's
blanket exclusions.

---

#### TC-10: `population_risk_tags` is offered to ENV only, and refused the same way a filter on it would be

**Objective.** Confirm a sensitive attribute below its clearance is absent from the panel exactly
as discover refuses it as a filter.

**Preconditions.** P1–P4.

**Test data.** `{}` as each of the three tokens.

**Given** `population_risk_tags` is flagged `sensitive` and needs SECRET clearance (discover's
[TC-18](product-discover.md#tc-18-population_risk_tags-is-filterable-by-env-only-secret))
**When** the panel is read
**Then** ENV's `filters[]` contains `attribute:product.population_risk_tags`
**And** HEG's and BCC's do **not**
**And** posting `{"filters":[{"attribute":"population_risk_tags","operator":"any_of","values":["children"]}]}`
to `/discover/filter` as HEG or BCC is **`403`**, `filter.attribute_not_permitted:population_risk_tags`
- the same refusal discover gives, since both run the same `ProductSearchCriteriaFactory.create`.

```bash
for t in ENV_TOKEN HEG_TOKEN BCC_TOKEN; do
  echo -n "$t: "; filters "${!t}" '{}' | jq 'any(.filters[]; .key=="attribute:product.population_risk_tags")'
done
```

*Why:* "absent from the panel" and "refused as a filter" must be the same decision, made once, not
two independently-maintained lists that could drift.

---

### D. `isOwnProduct`

#### TC-11: `isOwnProduct` lists both values with the caller's own split

**Objective.** Confirm the two-value, always-both rule, and that counts match the caller's
own/other split.

**Preconditions.** P1–P4.

**Test data.** `{}` as HEG.

**Given** HEG's 12 visible products: its own 6, plus 4 of ENV's and 2 of BCC's (discover §6.1)
**When** the panel is read
**Then** `field:isOwnProduct` carries exactly two values, in order:
`{"value": true, "count": 6, "selected": false}`, `{"value": false, "count": 6, "selected": false}`
**And** no `missingCount` key (every product reports one or the other; the field has no "absent"
state for a caller whose organisation is known).

```bash
filters "$HEG_TOKEN" '{}' | values_of field:isOwnProduct
```

*Why:* `isOwnProduct` always sums to `totalElements` exactly - 6 + 6 = 12 - so any mismatch is
either a counting bug or the `true`/`false` ordering rule not being special-cased.

---

#### TC-12: `isOwnProduct` is absent from the panel for a caller whose organisation is not known

**Objective.** Confirm the field is withheld, not shown with misleading counts, when the caller's
own identity is not established.

**Preconditions.** P1, P2, P4, **P5** (`application.opa.enabled=false`; see §G - this is the only
state in which a request lacking the `organisation` claim is not refused outright before reaching
the service, since `organisation.missing` denies it while policy is on).

**Test data.** `{}` with a token carrying the `product_discovery` role but **no** `organisation`
claim.

**Given** `EnhancedPrincipal.organisation()` substitutes the `UNKNOWN_ORG` sentinel for a missing
claim, which the service turns into "not known" at its entry
(`UnknownIdentifiers.knownOrganisationOrNull`, as discover and view do) before anything reads it
**When** the panel is read
**Then** `filters[]` contains **no** entry with `key: "field:isOwnProduct"`
**And** every other field and attribute is still listed (policy is off, so nothing else is
withheld).

```bash
filters "$NO_ORG_TOKEN" '{}' | jq 'any(.filters[]; .key=="field:isOwnProduct")'
# false
```

*Why:* reporting `isOwnProduct` with every value `false` (treating "unknown" as "not mine") would
be a quiet wrong answer rather than an honest absence - the same reasoning discover applies when it
omits the member from a result for the same caller.

---

### E. Free text

#### TC-13: A text term narrows every group's counts, including its own target's values

**Objective.** Confirm `text` behaves as a filter on every group's counts - unlike a `filters[]`
entry, `text` is **never** excluded from any group's own counts, because no filter target *is*
"free text".

**Preconditions.** P1–P4.

**Test data.** `{"text": "Bristol_Cycle%Counts"}` as BCC (discover's
[TC-07](product-discover.md#tc-07-a-text-term-is-a-value-never-a-pattern) gives exactly **1** for
this body, proving `%` and `_` are escaped, not wildcards).

**Given** BCC's baseline `record_unit` group: `geographic_area:4, household:2, person:1,
property:1, missingCount:0`
**When** the text term is posted
**Then** `totalElements` is **1**
**And** `textSearch.enabled` is still `true` (the term matched, it did not disable searching)
**And** the `record_unit` group narrows to `geographic_area:1, household:0, person:0, property:0,
missingCount:0` - `Bristol_Cycle%Counts`'s own `record_unit` is `geographic_area`, so only that
value keeps a non-zero count, and the other three remain listed at zero rather than disappearing.

```bash
filters "$BCC_TOKEN" '{"text":"Bristol_Cycle%Counts"}' \
  | jq '{total: .totalElements, record_unit: (.filters[] | select(.key=="attribute:product.record_unit") | .values)}'
```

**And** `{"text":"%"}` as BCC gives `totalElements: 0` and every group's counts at **zero** (not an
error, and not "everything", proving the term is bound as a value here exactly as in discover).

*Why:* text and filters narrow the same way syntactically, but a text term has no "own target" to
be excluded from, so the asymmetry in [the counting rule](#the-counting-rule) between `text` and
`filters[]` is deliberate, not an oversight - every group, including one a text match happens to
agree with, is narrowed by it.

---

### F. Refusals shared with discover

#### TC-14: A refused filter is `403` with the identical reason discover gives

**Objective.** Confirm the panel endpoint refuses exactly as discover does for the same body and
token - same status, same message, same `reasons`.

**Preconditions.** P1–P4.

**Test data.** `{"filters":[{"field":"source","values":["x"]}]}` as BCC (discover's
[TC-32](product-discover.md#tc-32-filterfield_not_permittedname)).

**Given** `source` is masked for BCC
**When** the body is posted to both endpoints as BCC
**Then** both are `403` with body
`{"status":403,"message":"Access denied by policy","reasons":["filter.field_not_permitted:source"],"errorId":"…"}`
(`errorId` differs between the two calls; everything else is identical)
**And** the same body as ENV or HEG is `200` on both endpoints.

```bash
diff <(filters  "$BCC_TOKEN" '{"filters":[{"field":"source","values":["x"]}]}' | jq 'del(.errorId)') \
     <(discover "$BCC_TOKEN" '{"filters":[{"field":"source","values":["x"]}]}' | jq 'del(.errorId)')
```

*Why:* because both run `ProductSearchCriteriaFactory.create` against the identical contract, a
divergence here can only mean the two endpoints are no longer sharing that contract - the single
most serious regression this endpoint could have, since it is the one the whole design exists to
prevent. Also applicable unchanged: discover's
[TC-33](product-discover.md#tc-33-filterattribute_not_permittedname),
[TC-36](product-discover.md#tc-36-an-unknown-name-is-refused-identically-to-a-forbidden-one) and
[TC-37](product-discover.md#tc-37-several-refusals-are-reported-at-once).

---

#### TC-15: Malformed bodies are `400`, identically to discover, for every caller

**Objective.** Confirm body-shape validation runs the same way, before policy, on this endpoint.

**Preconditions.** P1–P4.

**Test data.** Each row of discover's
[TC-41](product-discover.md#tc-41-body-shape-400s-refused-before-policy) table, posted to
`/discover/filter` as ENV **and** as BCC.

**Given** each malformed body
**When** it is posted to `/discover/filter`
**Then** the status is `400` with the identical message discover gives, **no `reasons` field**,
and identically for both callers - validation runs before the policy decision on this endpoint too.

```bash
filters "$ENV_TOKEN" '{not-json' ; echo
filters "$BCC_TOKEN" '{"page":-1}'
```

*Why:* this endpoint must not become a second place where a validation rule could be loosened or
tightened independently of discover's - both call the same `@Valid` binding on the same DTO.

---

#### TC-16: `sort`, `page` and `size` are accepted, validated and ignored

**Objective.** Confirm the three members with no effect here are still bound and validated, never
silently dropped at the JSON level, and never change the response.

**Preconditions.** P1–P4.

**Test data.** `{"sort":[{"field":"name","direction":"desc"}],"page":2,"size":3}` and
`{"sort":[{"field":"name","direction":"sideways"}]}`, as ENV.

**Given** ENV's baseline panel for `{}`
**When** the first body is posted
**Then** the response is `200` and **byte-identical** to `{}`'s response (`totalElements`,
`textSearch`, every group's values and counts all unchanged - there is no page 2 of a filter panel)
**When** the second body is posted
**Then** the response is **`400`**, the identical message discover's
[TC-41h](product-discover.md#tc-41-body-shape-400s-refused-before-policy) gives for the same
fragment, because the member is still validated even though the service never reads the result.

```bash
diff <(filters "$ENV_TOKEN" '{}') \
     <(filters "$ENV_TOKEN" '{"sort":[{"field":"name","direction":"desc"}],"page":2,"size":3}')
# no output
```

*Why:* "ignored" must mean "has no effect on the answer", not "skips validation" - a caller sending
a stray `sort` with a typo should get the same `400` discover would give, not a `200` that silently
threw the member away.

---

### G. Policy switched off

#### TC-17: With policy off, the panel is wide open, and sensitive attributes appear

**Objective.** Confirm the switched-off mode: nothing withheld, no `policy` block, and the
previously-masked sensitive attribute now listed - the supported, honestly-reported "everything" state.

**Preconditions.** P1, P2, P4, **P5** (`application.opa.enabled=false`, service restarted). OPA may
be stopped entirely.

**Test data.** `{}` as any token with the `product_discovery` role.

**Given** policy enforcement is off
**When** the panel is read
**Then** `totalElements` is **17** (every product, no row filter)
**And** `filters[]` has **no** `policy` key at the top level (absent, not `null`, not filled with
defaults - identical rule to discover's [TC-43](product-discover.md#tc-43-with-policy-off-every-caller-sees-the-whole-catalogue))
**And** `filters[]` now carries **all twelve** policy attributes, including the organisation-scoped
`authorised_classifications` and `permitted_purposes` and the product-scoped
`population_risk_tags` and `coverage_jurisdictions`, none of which any organisation's enforced
contract permits
**And** `attribute:product.population_risk_tags`'s values are
`children:2, domestic_abuse_survivors:2, protected_witnesses:1`, `missingCount: 13` (§6.2) -
exactly the values discover itself would now return unmasked for the same request, per
[TC-43](product-discover.md#tc-43-with-policy-off-every-caller-sees-the-whole-catalogue)
**And** `filters[]` still contains **no** `field:name`, `field:displayName`, `field:description`,
`field:longDescription`, `field:topic` or `field:source` entry - the panel's exclusion of those six is unconditional
(§5.1) and the master switch does not reopen it, unlike every attribute and every other field.

```bash
filters "$ENV_TOKEN" '{}' | jq 'has("policy")'
# false
filters "$ENV_TOKEN" '{}' | values_of attribute:product.population_risk_tags
```

*Why:* a filter panel is a second surface for exactly the same disclosure risk discover has, so it
must honour the master switch's honesty rule too: there is nothing truthful to report about who
*would* be withheld something when no decision was taken at all. If a `policy` block appeared here
full of defaults while discover's did not, the two endpoints would be lying about the same request
in two different ways.

---

#### TC-18: Switching policy back on restores both the masking and the `policy` block

**Objective.** Confirm the switch is the only difference, on this endpoint too.

**Preconditions.** P1–P4 (restart with `application.opa.enabled=true`).

**Given** TC-17 has just been run
**When** the service is restarted with policy on and TC-01 and TC-10 are repeated
**Then** `totalElements` returns to 15 / 12 / 8 per caller
**And** `population_risk_tags` is absent again for HEG and BCC
**And** the `policy` block reappears, identical to discover's for the same caller.

*Why:* the same reasoning as discover's [TC-44](product-discover.md#tc-44-switching-policy-back-on-restores-the-contract)
- a panel that stays "open" after the switch is flipped back on is the single most serious failure
mode either endpoint has.

---

## 9. Edge cases

| # | Case | Request | Expected | Why |
|---|---|---|---|---|
| E1 | **A declared value no visible product holds** | `{}` as ENV, inspect `attribute:product.population_risk_tags` | `rare_condition_cohorts` is **absent** from `values`, although it is a declared `allowed_values` entry | listing a declared-but-unused value would disclose the vocabulary rather than the data, which TC-22 of discover's spec specifically guards against for filtering; the panel must not reopen that gap for listing |
| E2 | **A target with no permitted values at all narrows to nothing** | an attribute filter that matches zero products, e.g. `{"filters":[{"attribute":"record_unit","values":["spaceship"]}]}` as ENV | `200`, `totalElements: 0`, every other group's counts all zero, every value still **listed** | mirrors discover's TC-22: an unknown value is data, not an error, and the panel must not drop rows just because nothing currently matches |
| E3 | **Metacharacters in `text`** | `{"text":"%"}` as BCC | `200`, `totalElements: 0`, every count zero | the term is bound as a value in the facet query exactly as in the count query (TC-13) |
| E4 | **SQL-injection-shaped `text` or filter value** | `{"text":"' OR 1=1 --"}`; `{"filters":[{"field":"name","values":["'; DROP TABLE product; --"]}]}` | `200` with every count at 0; never a `500`; `SELECT count(*) FROM mn.product` unchanged afterwards | the facet query binds the same way the count query does - one `SqlPredicateCompiler`, one set of bound parameters, reused |
| E5 | **An attribute name that does not exist** | `{"filters":[{"attribute":"no_such_attribute_at_all","values":["x"]}]}` | `403`, `filter.attribute_not_permitted:no_such_attribute_at_all` - same as discover's TC-36, **not** a `400` | the filter-options endpoint does not get its own, looser error path for an unrecognised name |
| E6 | **No filter is worth listing values for** | not reachable with the shipped sample policy (every enforced contract offers at least one of the six panel-eligible fields, and every panel-eligible field lists values - see §5.1); confirm instead that a caller whose contract permits **zero** panel-eligible fields and **zero** attributes still gets `200` with `filters: []`, not an error | `200`, `filters: []`, `totalElements` still correct | `ProductFacetQueryBuilder.build` throws on an **empty** target list by contract (`IllegalArgumentException`), so `ProductFilterOptionsServiceImpl.facetCounts` must short-circuit before calling it whenever no listing target is permitted - exercised at the unit level in `ProductFilterOptionsServiceImplTest`, not reachable through the sample policy |
| E7 | **The same request posted twice** | any body, twice, same token | byte-identical responses | counts are a pure function of caller, body and the catalogue's current state |
| E8 | **A caller holding the role but failing policy's organisation gate** | a token with `product_discovery` but no `organisation` claim, policy **on** | `403`, `reasons: ["organisation.missing"]` - the **same** gate discover's TC-39c hits, since the decision is taken before either service runs | this endpoint cannot be used to get a panel for an unidentified caller while policy is enforced; TC-12 is the one state (policy off) where that is even observable |

### Things that should never happen

1. A `500` from any input in this document.
2. `filters[]` containing `field:name`, `field:displayName`, `field:description`,
   `field:longDescription`, `field:topic` or `field:source` for **any** caller, policy on or off (§5.1's unconditional exclusion) - or
   `field:subscribedBy` for BCC, or `attribute:product.population_risk_tags` for HEG or BCC, while
   policy is enforced.
3. A value listed in `values[]` that no product the caller can see actually holds.
4. `totalElements` here differing from `POST /api/v1/product/discover`'s `page.totalElements` for
   the identical body and token.
5. A `policy` key present while `application.opa.enabled=false`, or absent while it is `true`.
6. A reason beginning `policy.` or `dispatch.` in a response body (same rule as discover).
7. The Organisation group's own counts changing when an `organisation.key` filter is added to the
   request (TC-07) - that is the one group a request can never narrow with its own choice.

## 10. Defect triage

| Symptom | Most likely cause |
|---|---|
| every group's counts collapse to the same single value when one filter is added | the "exclude this target's own filter" rule in `ProductFacetQueryBuilder.narrowedCondition` is excluding every filter, not just this target's; re-check with TC-08's two-filter case |
| a value disappears from a group after adding an unrelated filter | the value-listing query (ignoring request filters) is being re-run with the request's filters applied after all; re-check with TC-05 |
| `totalElements` here disagrees with discover's for the same body | the two endpoints have drifted from a shared `ProductQueryPlanner.compile` / `count` call; see TC-04 |
| a masked field or a below-clearance attribute appears in `filters[]` | `FilterOptionTargets.permitted` is not consulting the same `ProductSearchCriteriaFactory.permits` discover's refusal path uses; see TC-09/TC-10 |
| `field:name`, `field:displayName`, `field:description`, `field:longDescription`, `field:topic` or `field:source` appears in `filters[]` for any caller, or `source`/`subscribedBy` is missing from discover's own `policy.filterableFields` | `FilterOptionTargets.isOfferedInPanel` was weakened to consult the contract for one of these six, or discover's contract was changed to stop permitting them - the panel's exclusion is meant to be unconditional and independent of discover's own filtering; see TC-02 |
| `GROUP BY` / "must appear in the GROUP BY clause" error from PostgreSQL | a new value expression added to `ProductFacetQueryBuilder.valueExpression` binds a parameter (like `isOwnProduct`'s `CASE`) and is being grouped by the expression itself rather than by ordinal position 2 - see the class Javadoc |
| the `format` column, or its values, are missing or all-null | the two newest migrations (`V20261018120000`, `V20261018121000`) are not applied in this environment; see §6.1's note |
| every count is lower than expected by the same offset | an edited `Bristol_Cycle%Counts` or other row with missing fields is being counted as "missing" when a count was expected, or vice versa; re-derive from §6 before reporting |
| you want to see the compiled facet SQL | the same switch as discover's defect triage: set
`logging.level.uk.gov.dbt.ndtp.ia.node.management.service.data.impl.ProductFilterOptionsServiceImpl: DEBUG`
alongside discover's `ProductDiscoveryServiceImpl` one |

See discover's [§10](product-discover.md#10-defect-triage) for every symptom shared with the search
endpoint itself (identity sent three times, OPA down, a `403` where a `400` was expected, …) -
nothing there is repeated here.
