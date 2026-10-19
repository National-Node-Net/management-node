# Discover filter API: design

Date: 2026-10-18. Branch: `feature/DPAV-3395`.

## Intent

The catalogue UI needs a filter panel: which filters the caller may use, what values each one
has, and how many products sit behind each value. That panel has to react to what the caller has
already chosen. For example, choosing organisation `ENV` must narrow every other group to ENV's
products.

Today the UI fakes this:

- `ui/src/vocabulary.js` hard-codes every field, attribute and value list (`ENV/HEG/BCC`,
  `topic/file`, quality levels…). It is a snapshot and drifts from the catalogue.
- `ui/src/Facets.jsx` gets counts by firing one `discover` call with `size: 1` per value per group
  (13 requests per refresh), and only for the four groups someone wrote down.

The new endpoint gives the UI the whole panel in one request, built from the same policy decision
and the same search criteria as discover, so the panel can never show more than discover would.

## Goals

- `POST /api/v1/product/discover/filter` returns every filter the caller may use, with its data
  type, operators and, where meaningful, its values with counts.
- Same request body, same validation, same policy and same refusals as `POST /api/v1/product/discover`.
- Counts change with the request body (see [Counting](#counting)).
- A well-defined JSON shape the UI can render without its own vocabulary.
- The existing UI adopts it: both the left **Browse Products** rail (`Facets.jsx`) and the advanced
  **query builder** (`QueryBuilder.jsx`).

## Non-goals

- Returning products. That stays discover's job.
- A new policy rule or new entitlement logic. The filter endpoint answers from discover's rule.
- Filtering on scopes discover cannot filter on (consumer, producer, subscription attributes).

## Anti-goals (a failure even if it works)

- A count, value or filter that reveals something discover would withhold from the same caller:
  a product the row filter excludes, a masked field, a sensitive attribute's values.
- A second source of truth for "what can be filtered" that can drift from discover's contract.
- Cost that grows with the number of values (no query per value).

## Endpoint

| | |
|---|---|
| Method and path | `POST /api/v1/product/discover/filter` |
| Role | `product_discovery`, as discover |
| Policy | `@Policy(resource = "product", action = "discover_filter", details = ProductDiscoveryPolicyDecisionDetails.class)` |
| Rule | a route in `docker/opa/policies/routing/data.json` sends `product` / `discover_filter` to `product.discover`. Provenance then shows `resolution: route`, so the two endpoints are told apart in logs while sharing one rule |
| Request body | `ProductDiscoveryRequestDTO`, unchanged. `text` and `filters` drive the counts; `sort`, `page`, `size` are accepted and ignored |
| Validation | `ProductSearchCriteriaFactory`, unchanged, so a body that discover refuses with `400` or `403` is refused identically here |
| Planning | `ProductQueryPlanner.contractFor(...)`, unchanged, so the switched-off, missing-decision and unsupported-obligation rules apply as they do for discover |
| Policy switched off | open contract, as discover: every field and every product/organisation attribute in the catalogue, nothing masked |
| Audit | one line per request when the `audit_access` obligation is present, as discover: provenance and the criteria *names*, never values |

`discover_filter` passes startup validation (`[a-z][a-z0-9_]*`, not `fallback`). The route needs no
Rego change beyond `routing/data.json`, because `policies.product.discover` does not read
`input.action`.

## Response

```json
{
  "totalElements": 12,
  "textSearch": { "enabled": true, "fields": ["name", "displayName", "description"] },
  "filters": [
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
        { "value": "CSV", "count": 4, "selected": true },
        { "value": "JSON", "count": 3, "selected": false },
        { "value": "XML", "count": 0, "selected": false }
      ],
      "missingCount": 1,
      "truncated": false
    },
    {
      "key": "field:name",
      "target": { "field": "name" },
      "label": "Name",
      "dataType": "TEXT",
      "multiValued": false,
      "operators": ["eq", "neq", "in", "not_in", "any_of", "none_of", "contains", "exists", "not_exists"],
      "defaultOperator": "contains",
      "sortable": true
    },
    {
      "key": "attribute:organisation.jurisdictions",
      "target": { "scope": "organisation", "attribute": "jurisdictions" },
      "label": "Jurisdictions",
      "dataType": "TEXT",
      "multiValued": true,
      "operators": ["eq", "neq", "in", "not_in", "any_of", "none_of", "all_of", "contains", "exists", "not_exists"],
      "defaultOperator": "any_of",
      "sortable": true,
      "values": [ { "value": "England", "count": 9, "selected": false } ],
      "missingCount": 0,
      "truncated": false
    }
  ],
  "policy": { "...": "the policy block discover returns, unchanged; absent when policy is switched off" }
}
```

### Members

| Member | Meaning |
|---|---|
| `totalElements` | products matching the whole request, exactly discover's `page.totalElements` for the same body |
| `textSearch.enabled` / `fields` | whether free text is permitted and which fields it searches (`contract.textSearchFields()`) |
| `filters[]` | one entry per filter the caller may use, in the order below |
| `key` | stable id: `field:<apiName>` or `attribute:<scope>.<name>` (product scope written `product.<name>`). For UI state |
| `target` | copied verbatim into a discover `filters[]` entry; the UI adds `operator` and `values` |
| `label` | fields: a fixed label per `ProductField` in Java. Attributes: `policy_attribute_definition.display_name`, falling back to the name |
| `dataType` | `TEXT`, `NUMBER` or `BOOLEAN`. Fields: `ProductField.Kind`. Attributes: the definition's `data_type`, mapped (`STRING` → `TEXT`; unknown types → `TEXT`) |
| `multiValued` | attributes: the definition's flag. Fields: false, except `subscribedBy`, which behaves as a set |
| `operators` | the operators discover accepts for this target, from the same rules `ProductSearchCriteriaFactory` and `SqlPredicateCompiler` apply. They must never offer an operator discover would refuse |
| `defaultOperator` | what the UI should use for a value pick: `in` (or `any_of` for multi-valued) for enumerated filters, `contains` for free-text fields, `eq` for booleans |
| `sortable` | whether discover accepts it as a sort key |
| `values` | absent for free-text fields; see [Values](#values) |
| `missingCount` | products in the narrowed set with no value for this target (what `not_exists` would match). Present only when `values` is |
| `truncated` | true when `values` was cut at the cap |

Absent members are omitted, never `null`, matching the rest of the API.

### Which filters appear

- **Fields:** `ProductField` members that are filterable **and** that the contract permits
  (`contract.mayFilterOn`, i.e. allowed − masked). `id` never appears while policy is enforced,
  since no policy permits it.
- **Attributes:** live definitions bound to the `PRODUCT` or `ORGANISATION` scope, permitted by
  the contract (`allowed_filtered_attributes` − masked), and not sensitive when
  `mask_sensitive_attributes` is on.
- **The filter permission decides, not result visibility** (user decision). An attribute the
  contract lets the caller filter on is listed with its values and counts even when policy hides
  that block from the caller's results: BCC (OFFICIAL) sees `quality_designation` and `record_unit`
  values although its products carry no attributes. This discloses nothing discover does not
  already answer through `totalElements`, and the policy grants that search deliberately.
- **Order:** fields in `ProductField` order, then product attributes, then organisation attributes,
  each by label.

### Values

Per target, one rule decides which values are listed:

| Target | Values |
|---|---|
| `name`, `displayName`, `description` | none (free text) |
| `isOwnProduct` | `true` and `false`, always both. Present only when the caller's organisation is known |
| every other field, every attribute | the distinct values held by **at least one product the caller can see** (the row filter alone, ignoring the request's filters and text) |

- A listed value can have `count: 0`: it exists among the caller's visible products but not in the
  current narrowed set. The UI greys it out rather than removing it, so the panel does not jump
  around as the user clicks.
- A value no visible product holds is never listed, even when the attribute definition declares it
  in `allowed_values`. Declared values only affect ordering when present (see below).
- **Order:** count descending, then value ascending (case-insensitive).
- **Cap:** 50 values per target. Beyond that, `truncated: true`, and the UI offers free entry.
- Multi-valued attributes: each value a product holds counts that product once.
- Values are reported as stored (for example `CSV`, not `csv`). **Field** filters compare
  case-insensitively (`LOWER(col) IN ...`), so case variants of a field value are merged under the
  most common spelling. **Attribute** filters compare exactly (`a.item IN ...`), so attribute
  values are never merged: each count then equals what discover returns when that value is chosen.
  Discover's matching is not changed.

### Counting

This is standard disjunctive faceting:

> A value's `count` is the number of products that satisfy the policy's row filter, the request's
> `text`, and every request filter **except those on this same target**, and that hold the value.

So choosing organisation `ENV` narrows Format, Quality and every other group to ENV's products,
while the Organisation group still shows HEG's and BCC's counts so the user can see what switching
would give. Several filters on one target are all excluded from that target's own counts.

`selected` is true when the request has an `eq`, `in` or `any_of` filter on this target containing
that value (compared case-insensitively). Other operators (`neq`, `contains`…) still narrow the
other groups but select no value.

## Constraints

- Everything a count is computed over is compiled through the existing `SqlPredicateCompiler` and
  the contract's row filter, so nothing withheld is read. No post-filtering in Java.
- A fixed number of SQL statements per request, independent of how many values exist: at most one
  per filter group, or one combined statement. No statement per value.
- Mockito unit tests only, no database (`CLAUDE.md`). Follow `NetworkMapRepositoryTest` for SQL
  assertions.
- Migrations immutable; none expected for this feature.
- Spotless before handing back; SPDX header on new files.

## UI adoption

- `api.js`: `discoverFilters(criteria)` posting to the new endpoint.
- **Left rail (`Facets.jsx`):** rendered from `filters[]` entries that have `values`, replacing
  `GROUPS` and the per-value `discover` calls. One request per change of the search. Selection
  becomes **multi-select within a group** (OR, sent as one `in`/`any_of` clause) and AND across
  groups. Zero-count values greyed; `truncated` groups show a "more" affordance that opens the
  query builder for that target.
- **Query builder (`QueryBuilder.jsx`):** its field and attribute pickers, operator lists and value
  suggestions come from `filters[]` (`target`, `dataType`, `operators`, `values`).
- `vocabulary.js`: `FIELDS`, `ATTRIBUTES` and their value lists are removed. Operator labels and
  arity (`OPERATORS`) stay, since they are presentation. Everything that read them moves to the new
  response: `contract.js` (`filterableFields`, `filterableAttributes`, `sortableFields`,
  `sortableAttributes`, `findTarget`) is re-implemented over `filters[]`, with sorting taken from
  `sortable`; `Catalogue.jsx` and `Controls.jsx` stop using `FACET_GROUPS` and
  `attributesByQualifiedName` and read groups and labels from the same response.
- **Which clauses the rail owns:** the rail owns the `in` (or `any_of`, for multi-valued) clause on
  each target. Picking a value creates that clause or adds to it; un-picking removes the value, and
  the clause when empty. Clauses the query builder adds with any other operator (`neq`,
  `contains`, `exists`…) are left alone by the rail and still narrow the other groups. If the
  builder adds an `eq`/`in`/`any_of` clause, the rail shows its values as selected and edits it.
- The rail refreshes after every change to the search (text, filters), since counts now depend on
  the selection.

## Tests and docs

- Java: controller (role, `@Policy`, body passthrough, JSON shape), the service (which filters
  appear, open vs enforced contract, sensitive and masked exclusion, `isOwnProduct` only with a
  known caller), the count query SQL (row filter always applied, own-target filters excluded,
  others included, schema prefix), the result mapping (`selected`, `missingCount`, `truncated`,
  ordering, case merging), and `PolicyAnnotationValidatorTest` still passing.
- Rego: a `dispatch_test.rego` case that `product` / `discover_filter` resolves by route to
  `product.discover` with an identical decision.
- Docs: a test specification `docs/tests/products/product-discover-filter.md` in the style of
  `product-discover.md`; a row in `AUTHENTICATION_REQUIREMENTS.md`; the endpoint in
  `POLICY_ENFORCEMENT.md` reference endpoints.

## Left to the builder

- Class names and package layout (following the `service/discovery` pattern).
- One statement per group versus a single `UNION ALL`, as long as the statement count is bounded
  as above.
- The exact field labels.
- UI styling of greyed values and the "more" affordance.

## Calls made without explicit sign-off

These were my choices during the conversation and should be checked:

1. The route-based policy wiring (`discover_filter` → `product.discover`) rather than annotating
   with action `discover` directly.
2. Free-text fields (`name`, `displayName`, `description`) list no values.
3. Declared `allowed_values` are not listed unless a visible product holds them (keeps the rule
   "only values the caller can see", which the user agreed to).
4. 50-value cap per group.
5. Multi-select within a rail group.
