# Product Discovery and View: Test Scenarios

**Repository:** `management-node`
**Endpoints:** `POST /api/v1/product/discover`, `GET /api/v1/product/{productId}`
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`

---

Scenarios for a human tester, over the sample data this repository ships. Each one states what to
send, what to expect, and **why**, so that when an answer differs you can tell a bug from a data
change.

> The product counts and refusals below are not predictions: they were produced by running the real
> policy (the rule in `docker/opa/policies/product/discover.rego`, evaluated by OPA) through the
> real service against a PostgreSQL database loaded with these migrations. What has **not** been
> exercised end to end is the HTTP layer in front of it (mTLS, tokens and roles), which is what
> these scenarios ask you to do.

The one idea to hold on to: **policy decides what a caller can find, and the service turns that
decision into the database query.** Both endpoints do it with the same contract: viewing one
product is a search constrained to that product, so what you can view is exactly what you can
discover (§5a). So a difference in results is either a difference in the
calling organisation's attributes, a difference in the product's attributes, or a bug. Nothing in
Java decides who sees what.

> **Looking for a formal test specification?** These scenarios are the guided tour; they explain
> the system by walking through it. The per-API checklists, written as test requirement
> specifications with objectives, preconditions, test data and Given/When/Then, are in
> [`docs/tests/products/`](tests/products/README.md):
> [Product Discover](tests/products/product-discover.md) and
> [Product View](tests/products/product-view.md).

## Contents

- [0. If all three organisations return the same products](#0-if-all-three-organisations-return-the-same-products)
- [1. What you are testing](#1-what-you-are-testing)
- [2. Setting up](#2-setting-up)
- [3. Getting a token](#3-getting-a-token)
  - [3.1 Who is who](#31-who-is-who)
- [4. The sample catalogue](#4-the-sample-catalogue)
- [5. Scenarios with policy ON](#5-scenarios-with-policy-on)
- [5a. Viewing one product](#5a-viewing-one-product)
- [6. Scenarios with policy OFF](#6-scenarios-with-policy-off)
- [7. Bad requests and refusals](#7-bad-requests-and-refusals)
- [8. Things that should never happen](#8-things-that-should-never-happen)
- [9. Troubleshooting](#9-troubleshooting)

---

## 0. If all three organisations return the same products

The most common first result, and almost never a filtering bug. **Different callers must see
different catalogues** (15 / 12 / 8 in S1); identical counts mean policy is not shaping the query.
One command tells you which of three causes it is:

```bash
discover "$BCC_TOKEN" '{}' | jq '{total: .page.totalElements, policy}'
```

| What you see | Cause | Fix |
|---|---|---|
| `policy` is present and the counts are **each organisation's own** (15 / 12 / 8) | **You are sending the same identity every time.** The commonest cause by far: one browser session, one saved Postman token, or the same user re-logged-in. The organisation comes from the **token**, not from the request. | Check the `organisation` claim of each token (§3) - and see §3.1 for who is who. |
| `policy` is **absent** | **Policy enforcement is off.** No decision was taken, so the search ran with the open contract: every product, nothing withheld, the same for everyone. | Set `application.opa.enabled=true` (§2.4). The `local` profile does **not** set it; the default in `application.yml` is `false`, and only the `dev` profile turns it on. |
| `policy` is present but **identical** for all three callers | All three tokens resolve to the **same organisation**, or to none. | Decode the tokens (§3) and check each carries its own `organisation` claim: `ENV`, `HEG`, `BCC` (§2.2). |
| `policy` differs per caller but counts match | A genuine bug, or the catalogue does not hold what you think. | Compare against §4.2, then report it. |

Two crosschecks worth doing at the same time:

```bash
# Is policy enforcement actually on? The service says so once, at startup:
#   WARN  OPA is switched off in configuration (application.opa.enabled=false) ...
# If that line is in the log, that is your answer.

# How many products does the database actually hold?  Expect 17.
docker exec <postgres-container> psql -U <user> -d <db> -c 'SELECT count(*) FROM mn.product;'
```

If every caller returns *the same number and that number is the whole catalogue*, policy is off. If
every caller returns the same number and it is **not** the whole catalogue, the callers are being
treated as the same organisation.

> A search that runs unrestricted because policy was **meant** to judge it and did not is refused,
> not answered: `403` with `policy.enforcement_missing` in the log. So "policy is on but silently
> not applied" cannot present as a successful search. Identical results with `policy` absent always
> means the switch is off.

---

## 1. What you are testing

A search over data products in which policy controls three separate things. Keeping them apart
makes most surprises self-explaining:

| Policy controls | Effect when it says no | How you see it |
|---|---|---|
| what may be **asked** | the request is refused | `403` naming the filter, e.g. `filter.attribute_not_permitted:identifiability` |
| what is **shown** | that member is absent from each result | the JSON has no such key; `policy.maskedFields` says why |
| what **exists** for this caller | the product is not in the results at all | shorter `products` list and smaller `totalElements` |

The third is the important one to understand: a product a caller may not discover is **not
returned and not counted**. It is indistinguishable from a product that does not exist. That is
deliberate, since a count is a disclosure.

---

## 2. Setting up

### 2.1 Infrastructure

Bring up Keycloak + Postgres, Vault and OPA (`/stack-up`, or follow `README.md`). Discovery needs
**Keycloak, Postgres and OPA**; Vault is only used by the certificate endpoints.

> **OPA fails closed.** If OPA is not running, every discover request returns `403`. That is
> correct behaviour, not a bug. Check `curl -s http://localhost:8181/health` first whenever you
> see an unexplained `403`.

### 2.2 Identity provider

The sample federator clients need two things that were **not** previously configured: the
`product_discovery` role, and an `organisation` claim. Both are now in
`docker/keycloak/tofu`: `clients.tf` defines the three `product_*` roles, `terraform.tfvars` maps
them onto `FEDERATOR_ENV` / `FEDERATOR_HEG` / `FEDERATOR_BCC`, and the `federator_client` module
adds a hardcoded `organisation` claim per client.

```bash
cd docker/keycloak/tofu
tofu init -backend-config=backends/dev-backend.tfvars   # state is remote (S3); needs credentials
tofu apply -var-file=terraform.tfvars
```

If you have no state-backend access, the same two changes take a minute in the Keycloak admin
console (`http://localhost:8080`, realm `mng-node`), and doing it by hand is a good way to see
exactly what the token needs:

1. **Client roles:** on the `management-node` client, add the roles `product_discovery`,
   `product_view`, `product_subscribe`. Then for each of `FEDERATOR_ENV`, `FEDERATOR_HEG`,
   `FEDERATOR_BCC`: *Service account roles* → *Assign role* → filter by clients → assign all three.
2. **Organisation claim:** on each federator client: *Client scopes* → *`<client>`-dedicated* →
   *Add mapper* → *By configuration* → *Hardcoded claim*, with token claim name `organisation` and
   value `ENV`, `HEG` or `BCC` respectively. Tick *Add to access token*.

**Both parts matter, and they fail differently:**

| Missing | Symptom |
|---|---|
| the `product_discovery` role | `403 "Access denied: insufficient permissions for this operation"`, refused by the role check, before policy |
| the `organisation` claim | `403 "Access denied by policy"` with reason `organisation.missing`, because policy cannot resolve the caller's attributes |

The claim value must be the key **as the database holds it** (`ENV`), not the client id
(`FEDERATOR_ENV`). They are matched against `organisation.organisation_key`.

### 2.3 Data

The sample catalogue is in `src/main/resources/db/samples/` and is applied by Flyway at startup
alongside the real migrations. Nothing extra to run, but confirm it landed:

```sql
SELECT count(*) FROM mn.product;                 -- expect 17
SELECT count(*) FROM mn.policy_attribute_value;  -- expect well over 100
```

### 2.4 The application

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

`keystore.jks` and `truststore.jks` must sit in the **repo root**, and every call needs the client
certificate. Policy enforcement must be on:

```yaml
application:
  opa:
    enabled: true          # or OPA_ENABLED=true
```

The `dev` profile turns it on, along with `log-input` and `log-output`, very useful while testing,
since they print the exact document sent to the PDP and the decision that came back.

---

## 3. Getting a token

```bash
get_token() {
  curl -sk --cert client.crt --key client.key \
    'https://localhost:8443/realms/mng-node/protocol/openid-connect/token' \
    -d "client_id=$1" -d 'grant_type=client_credentials' | jq -r .access_token
}

ENV_TOKEN=$(get_token FEDERATOR_ENV)
HEG_TOKEN=$(get_token FEDERATOR_HEG)
BCC_TOKEN=$(get_token FEDERATOR_BCC)
```

**Check the token before blaming the endpoint**; most first-run failures are here:

```bash
echo "$ENV_TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq '{aud, organisation, roles: .resource_access."management-node".roles}'
```

You want `aud` to include `management-node`, `organisation` to be `ENV`, and the roles to include
`product_discovery`.

### 3.1 Who is who

The organisation reaching policy is the one on the **token**, so which identity you authenticate as
is what varies the results, not anything in the request body. Two kinds of caller exist, and they
carry the claim differently:

| Caller | Identity | organisation |
|---|---|---|
| service account | client `FEDERATOR_ENV` / `FEDERATOR_HEG` / `FEDERATOR_BCC` | on the **client** (the hardcoded mapper of §2.2) |
| a person | realm user `jamesbond` / `heguser` / `nikannegaresh` | on the **user**, as an `organisation` attribute |

Through the UI client (`ia-data-product-catalogue-ui`) you are the second kind, so the organisation
is whoever is **logged in**:

| User | organisation | Expect (empty criteria) |
|---|---|---|
| `jamesbond` | ENV | **15** products |
| `heguser` | HEG | **12** products |
| `nikannegaresh` | BCC | **8** products |

So getting 15 three times in a row usually means three requests as `jamesbond`. Confirm before
anything else; the principal is logged on every request:

```
Set SecurityContextHolder to CustomJwtAuthenticationToken
  [Principal=CustomPrincipal{subject='…', clientId='…', organisation='ENV'}, …
```

and the decision input logs it too (`subject.organisation : ENV attributes={…}`). If that says
`ENV` on all three, you have your answer. Log out and back in as another user; a cached session or
a saved bearer token in Postman will otherwise keep sending the first identity.

A convenience wrapper used throughout the rest of this document:

```bash
discover() {   # discover <TOKEN> '<json body>'
  curl -sk --cert client.crt --key client.key \
    -X POST 'https://localhost:8090/api/v1/product/discover' \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "${2:-\{\}}"
}
```

---

## 4. The sample catalogue

### 4.1 The three organisations

Everything below follows from these attributes. They are in
`db/samples/V20260910130000__add_sample_organisation_policy_attributes.sql`, and changing them
changes every expected result in this document.

| | ENV (Environment Agency) | HEG (Homes England) | BCC (Bristol City Council) |
|---|---|---|---|
| clearance | **SECRET** | **OFFICIAL-SENSITIVE** | **OFFICIAL** |
| purposes | service delivery, regulatory oversight | service delivery, statistical analysis | service delivery |
| jurisdictions | England, Wales | England, Scotland | England, **Bristol** |
| remit | national | national | **local** (Bristol is not a nation) |

What each fact buys the caller:

- **clearance** decides which `identifiability` levels are discoverable, whether risk-tagged
  products are discoverable at all, what is masked, and the page-size cap (100 / 50 / 20);
- **purpose** decides which `quality_designation` values are discoverable: statistical analysis
  earns `experimental`, regulatory oversight earns `provisional`, everyone gets `validated`;
- **local remit** adds a jurisdiction condition: a local caller only discovers products whose
  `coverage_jurisdictions` overlap its own. This is the clearest demonstration that organisation
  attributes drive the query.

Plus one rule that cuts across all of it: **an organisation always discovers its own products**,
whatever their attributes, and sees who is using them.

### 4.2 The 17 products

Owner is the organisation behind the producer. `n/a` means the attribute is not recorded.

| Product | Owner | identifiability | quality | risk tags | coverage |
|---|---|---|---|---|---|
| FloodRiskMapZones | ENV | non_personal | validated | n/a | England, Wales |
| RiverLevelTelemetry | ENV | non_personal | validated | n/a | England, Wales |
| CoastalErosionForecast | ENV | non_personal | experimental | n/a | England |
| PollutionIncidentReports | ENV | pseudonymised | provisional | n/a | England, Wales |
| WaterAbstractionLicences | ENV | directly_identifiable | validated | n/a | England |
| UnclassifiedDraftDataset | ENV | **n/a** | **n/a** | n/a | England |
| BrownfieldLandAvailability | HEG | pseudonymised | validated | n/a | England |
| AffordableHousingCompletions | HEG | anonymised | validated | n/a | England |
| HelpToBuyApplicants | HEG | directly_identifiable | validated | children | England |
| HousingNeedSurvey | HEG | pseudonymised | experimental | n/a | England, Scotland |
| EmptyHomesRegister | HEG | pseudonymised | **superseded** | n/a | England |
| ScotlandLandRegisterExtract | HEG | anonymised | validated | n/a | **Scotland only** |
| PendingPlanningApplications | BCC | directly_identifiable | provisional | domestic_abuse_survivors, protected_witnesses | Bristol |
| BristolAirQualitySensors | BCC | non_personal | validated | n/a | Bristol |
| BristolSchoolPlaces | BCC | anonymised | provisional | children | Bristol |
| BristolTemporaryAccommodation | BCC | directly_identifiable | validated | domestic_abuse_survivors | Bristol |
| `Bristol_Cycle%Counts` | BCC | non_personal | validated | n/a | Bristol |

Four of these exist to be awkward, and each has a scenario of its own:

- **UnclassifiedDraftDataset** has no `identifiability` and no `quality_designation`; it tests
  that a missing attribute **fails closed** (S9);
- **`Bristol_Cycle%Counts`** has `%` and `_` in its name, and no description and no product type,
  it tests LIKE escaping and null handling (S10, S11);
- **ScotlandLandRegisterExtract** is harmless data that a Bristol caller still cannot discover,
  because of jurisdiction alone (S6);
- **EmptyHomesRegister** is `superseded`, a quality nobody is granted; its owner discovers it
  anyway (S7).

---

## 5. Scenarios with policy ON

### S1: Each organisation discovers a different catalogue

The baseline. Send an empty body as each caller: no criteria, so the result is exactly "everything
this caller may discover".

```bash
for t in "$ENV_TOKEN" "$HEG_TOKEN" "$BCC_TOKEN"; do
  discover "$t" '{}' | jq '{total: .page.totalElements, names: [.products[].name] | sort}'
done
```

| Caller | `totalElements` | Discovers |
|---|---|---|
| **ENV** | **15** | everything except HousingNeedSurvey and EmptyHomesRegister |
| **HEG** | **12** | its own 6, plus FloodRiskMapZones, RiverLevelTelemetry, CoastalErosionForecast, PollutionIncidentReports, BristolAirQualitySensors, `Bristol_Cycle%Counts` |
| **BCC** | **8** | its own 5, plus FloodRiskMapZones, RiverLevelTelemetry, AffordableHousingCompletions |

Read a few of these back to the table in §4 and the reasons should be plain:

- ENV misses **HousingNeedSurvey** and **EmptyHomesRegister**: top clearance, but neither
  `experimental` nor `superseded` is a quality ENV's purposes earn. Clearance is not everything.
- HEG discovers **CoastalErosionForecast** (experimental) and ENV does not: HEG holds
  *statistical analysis*. A lower-cleared organisation discovering something a higher-cleared one
  cannot is correct.
- HEG does not discover **BristolSchoolPlaces** even though anonymised and provisional would pass:
  it is tagged `children`, and risk-tagged products need SECRET.
- BCC discovers only three products it does not own, and every one of them covers England.

Note the paging: the first page holds at most the caller's cap (ENV 100, HEG 50, BCC 20), so all
three fit on one page. `totalElements` counts only what the caller may discover.

### S2: The same product, seen differently

`FloodRiskMapZones` is discoverable by all three. Compare what each is shown:

```bash
for t in "$ENV_TOKEN" "$HEG_TOKEN" "$BCC_TOKEN"; do
  discover "$t" '{"text":"FloodRiskMapZones"}' \
    | jq '.products[0] | {name, source, attributes, organisation: .organisation.key,
                          producer: .producer.name, consumers, subscribedBy}'
done
```

| Member | ENV | HEG | BCC |
|---|---|---|---|
| `name`, `topic`, `type`, `description` | ✅ | ✅ | ✅ |
| `organisation`, `producer` | ✅ | ✅ | ✅, since anyone who can discover a product sees who offers it |
| `source` | ✅ | ✅ | ❌ absent |
| `attributes` (the product's own) | ✅ | ✅ | ❌ absent entirely |
| `consumers` | ✅ | ❌ absent | ❌ absent |
| `subscribedBy` | ✅ | ✅ | ❌ absent |

Also check the `policy` block, which is how a UI would build its filter panel:

```bash
discover "$BCC_TOKEN" '{}' | jq .policy
```

BCC should show `maxPageSize: 20`, `maskedFields` including `source`, `consumers`, `subscribedBy`
and `policyAttributes`, and `obligations` including `audit_access` and `mask_response`.

### S3: An organisation always sees its own

This is the exception that makes masking legible. `PendingPlanningApplications` is BCC's own and is
directly identifiable and risk-tagged, BCC discovers it regardless, and sees who uses it, even
though `consumers` and `subscribedBy` are masked for BCC in general (S2).

```bash
discover "$BCC_TOKEN" '{"text":"PendingPlanningApplications"}' \
  | jq '.products[0] | {name, consumers, subscribedBy}'
```

**Expect:** the product is returned, and `consumers` / `subscribedBy` are **present on this product**
while still absent from every product BCC does not own (compare with FloodRiskMapZones in the same
response). Confirm the contrast in one call:

```bash
discover "$BCC_TOKEN" '{}' | jq '[.products[] | {name, hasSubscribedBy: (.subscribedBy != null)}]'
```

### S4: Who is using a product

`BristolAirQualitySensors` is used by three consumers across two organisations (ENV ×2, HEG ×1),
so `subscribedBy` and `consumers` are genuinely different things.

```bash
discover "$ENV_TOKEN" '{"text":"BristolAirQualitySensors"}' \
  | jq '.products[0] | {subscribedBy, consumerCount: (.consumers | length)}'
```

**Expect:** `subscribedBy` has **two** entries: ENV with `consumers: 2` and `since` the earlier of
its two grants (`2026-02-11T07:00:00`), and HEG with `consumers: 1`. `consumers` has **three**
entries, each with its subscription terms. Check that no subscription carries a `destination`
(delivery detail is deliberately not part of discovery).

### S5: Free text searches name and description, nothing else

```bash
discover "$ENV_TOKEN" '{"text":"flood"}'  | jq '[.products[].name]'
discover "$ENV_TOKEN" '{"text":"kafka"}'  | jq '[.products[].name]'
```

**Expect:** `"flood"` matches **FloodRiskMapZones** (by name) and **RiverLevelTelemetry** (its
description ends "Used for flood warning"): a case-insensitive substring in either. `"kafka"`
matches **nothing**, because `source` and `topic` are not text-searched even though `kafka://`
appears in several sources. They remain available as explicit filters.

### S6: Organisation attributes drive the query

The jurisdiction case. `ScotlandLandRegisterExtract` is anonymised and validated, so nothing about it
is sensitive, yet BCC cannot discover it, purely because BCC's remit is local and the product
covers Scotland only.

```bash
discover "$BCC_TOKEN" '{"text":"Scotland"}' | jq '.page.totalElements'   # expect 0
discover "$HEG_TOKEN" '{"text":"Scotland"}' | jq '[.products[].name]'    # expect it, and HousingNeedSurvey
```

**Expect:** 0 for BCC, and for HEG both **ScotlandLandRegisterExtract** and **HousingNeedSurvey**
(its description mentions Scotland): HEG's own products, and its jurisdiction covers Scotland.
This is the scenario to point at when someone asks "what does policy actually *do* here": no Java
code knows the word `Scotland`.

### S7: Filtering on fields and attributes

```bash
# by product field
discover "$ENV_TOKEN" '{"filters":[{"field":"type","values":["file"]}]}' | jq '[.products[].name]'

# by policy attribute
discover "$ENV_TOKEN" '{"filters":[{"attribute":"record_unit","operator":"in",
                                    "values":["geographic_area","property"]}]}' | jq '[.products[].name]'

# by the owning organisation
discover "$ENV_TOKEN" '{"filters":[{"scope":"organisation","field":"key","values":["HEG"]}]}' \
  | jq '[.products[].name]'

# by who is using it
discover "$ENV_TOKEN" '{"filters":[{"field":"subscribedBy","operator":"any_of","values":["BCC"]}]}' \
  | jq '[.products[].name]'

# several filters are AND-ed
discover "$ENV_TOKEN" '{"filters":[{"field":"type","values":["file"]},
                                   {"attribute":"quality_designation","values":["validated"]}]}' \
  | jq '.page.totalElements'
```

**Expect:** each narrows the S1 list and never widens it. The organisation filter returns only HEG
products **that ENV may discover** (so not HousingNeedSurvey or EmptyHomesRegister). The
`subscribedBy` filter returns the four products BCC's consumers hold a grant on:
RiverLevelTelemetry, PollutionIncidentReports, AffordableHousingCompletions and FloodRiskMapZones
(the last granted to `BCC-CONSUMER-1` by the original sample data, `V20250728152300`, not by the
discovery dataset).

Worth checking explicitly: a filter cannot reach past the row filter. Ask BCC to filter for a
product it cannot discover and you get an empty result, not a refusal,

```bash
discover "$BCC_TOKEN" '{"text":"WaterAbstractionLicences"}' | jq '.page.totalElements'  # 0
```

because a caller must not be able to tell "you may not see this" from "this does not exist".

### S8: Sorting and paging

```bash
discover "$ENV_TOKEN" '{"sort":[{"field":"name","direction":"desc"}],"size":5}' \
  | jq '{names: [.products[].name], page}'
discover "$ENV_TOKEN" '{"size":5,"page":1}' | jq '{names: [.products[].name], page}'
discover "$ENV_TOKEN" '{"size":5,"page":2}' | jq .page      # the last page of ENV's 15
discover "$BCC_TOKEN" '{"size":500}'        | jq '.page.size'
```

**Expect:** descending name order; page 1 continues where page 0 stopped with no repeats and no
gaps; and BCC's `size: 500` is **clamped to 20**, not refused; the response reports the size that
was actually applied. Totals stay the same across pages.

The `page` object answers four separate questions, and the two counts are the ones worth keeping
apart:

| Field | Means | On ENV's page 2 of `size: 5` |
|---|---|---|
| `number` | which page this is, from 0 | `2` |
| `size` | the page size **applied**, after clamping | `5` |
| `numberOfElements` | how many products **this response carries** | `5` |
| `totalElements` | how many match in all, for this caller | `15` |
| `totalPages` | how many pages of `size` those fill | `3` |

`size` and `numberOfElements` part company on the last page and whenever fewer products match than
a page holds: ENV's 15 products at `size: 4` give `numberOfElements: 3` on page 3, and any search
returning nothing gives `numberOfElements: 0`. Check `.products | length` equals
`.page.numberOfElements` in every response; they are the same number, and a mismatch would mean
something was removed after the page was counted.

Paging is exact because the database does it: `totalElements` and `totalPages` agree with what you
can actually walk through. Walk every page of ENV's catalogue with `size: 4` and you should collect
exactly the 15 names from S1, each once, across four pages whose `numberOfElements` are 4, 4, 4, 3.

### S9: A missing attribute is not a free pass

`UnclassifiedDraftDataset` (ENV's) records no `identifiability` and no `quality_designation`.

```bash
discover "$ENV_TOKEN" '{"text":"UnclassifiedDraftDataset"}' | jq '.page.totalElements'  # 1, own product
discover "$HEG_TOKEN" '{"text":"UnclassifiedDraftDataset"}' | jq '.page.totalElements'  # 0
discover "$BCC_TOKEN" '{"text":"UnclassifiedDraftDataset"}' | jq '.page.totalElements'  # 0
```

**Expect:** only its owner discovers it. For everyone else the missing attribute fails every
positive condition, so the product is withheld. **If HEG or BCC can see it, that is a serious bug**
It would mean an unclassified product is discoverable by default.

### S10: Search terms are values, never SQL

```bash
discover "$BCC_TOKEN" '{"text":"Bristol_Cycle%Counts"}' | jq '[.products[].name]'
discover "$BCC_TOKEN" '{"text":"%"}'                    | jq '.page.totalElements'
discover "$ENV_TOKEN" '{"text":"'"'"' OR 1=1 --"}'      | jq '.page.totalElements'
```

**Expect:** the first finds exactly `Bristol_Cycle%Counts`: `%` and `_` are matched literally, not
as wildcards. The second returns **0**, not everything. The third returns **0** and no error: the
term is a bound parameter, and it matches no product name or description.

Try the same through a filter value and an attribute name; both must behave identically.

### S11: Products with nothing recorded

`Bristol_Cycle%Counts` has no description and no product type.

```bash
discover "$BCC_TOKEN" '{"text":"Cycle"}' | jq '.products[0]'
```

**Expect:** the product is returned and found by name; `description` and `type` are **absent**
(not `null`, not `""`). A null description simply does not match a text search; it never errors.

---

## 5a. Viewing one product

`GET /api/v1/product/{productId}` is **the same policy, constrained to one product**. It is worth
testing right after S1, because the single most valuable check is that the two endpoints agree.

> Measured before release, driving the real rules through the real services against a PostgreSQL
> loaded with this data: every organisation views exactly what it discovers: ENV 15, HEG 12,
> BCC 8. A non-existent id returns the same `404` as a withheld one. Note ENV views **15 of the 17**
> products in the catalogue: before this work any sufficiently cleared caller could read all 17 by
> id, which is the hole S17 exists to catch.

```bash
view() {   # view <TOKEN> <productId>
  curl -sk --cert client.crt --key client.key -o /dev/null -w '%{http_code}\n' \
    "https://localhost:8090/api/v1/product/$2" -H "Authorization: Bearer $1"
}
view_body() {
  curl -sk --cert client.crt --key client.key \
    "https://localhost:8090/api/v1/product/$2" -H "Authorization: Bearer $1"
}
```

### S17: You can view exactly what you can discover

The guarantee the design rests on. Take the ids a caller discovers, and the ids they do not, and
check each one:

```bash
# every id BCC discovers should be 200
for id in $(discover "$BCC_TOKEN" '{}' | jq -r '.products[].id'); do
  echo "$id -> $(view "$BCC_TOKEN" "$id")"
done

# every id BCC does NOT discover should be 404 - take them from ENV's larger catalogue
mine=$(discover "$BCC_TOKEN" '{}' | jq -r '.products[].id' | sort)
for id in $(discover "$ENV_TOKEN" '{}' | jq -r '.products[].id' | sort); do
  grep -qx "$id" <<<"$mine" || echo "not discoverable by BCC: $id -> $(view "$BCC_TOKEN" "$id")"
done
```

**Expect:** every discoverable id `200`, every non-discoverable id **`404`**. A `200` in the second
loop is a serious bug; it would mean an id is a way around discovery's filtering, which is exactly
what this endpoint used to allow.

### S18: A withheld product is indistinguishable from a missing one

```bash
view "$BCC_TOKEN" 999999    # no such product
view "$BCC_TOKEN" <an id BCC cannot discover, from S17>
```

**Expect: `404` from both, with identical bodies.** Not `403` for the second. The caller was already
allowed to *ask*, which is what the role and the request-level policy check decide, so the
per-product answer must not reveal whether the product exists. Compare the two responses byte for
byte.

### S19: The same masking as discovery

```bash
view_body "$BCC_TOKEN" <a product BCC can discover> | jq
view_body "$ENV_TOKEN" 3 | jq
```

**Expect:** exactly the member-by-member outcome of S2 for that caller: BCC sees no `source`, no
`attributes`, no `consumers`, no `subscribedBy`; ENV sees everything; HEG sees `subscribedBy` but not
`consumers`. The response is the same object model discovery returns, so a product you discovered
and then viewed looks the same. Check `id` is present (it is what you pass to `subscribe`).

Own products unmask here too, as in S3: BCC viewing its own `PendingPlanningApplications` sees
`consumers` and `subscribedBy`.

### S20: The caller-level refusal is a 403, and applies to both endpoints alike

View applies **the same three caller gates discover applies**, from one shared definition: a known
organisation, a UK jurisdiction, and some clearance. There is no view-only clearance floor, so if you
may discover, you may view.

```bash
view "$BCC_TOKEN" 3          # BCC is OFFICIAL: allowed, like discover
```

**Expect `200`.** BCC holds only `OFFICIAL`, and that is enough for both endpoints. What its
clearance costs it is *fields*, not the endpoint: `source`, `consumers`, `subscribedBy` and the
product attribute map are withheld (S19), exactly as they are in discovery's results.

A caller the **gates** refuse (no organisation on the token, a non-UK jurisdiction, or no
clearance at all) gets `403` from *both* endpoints with the same reason. That is the difference
worth keeping straight: `403` means "not you, for anything"; `404` means "not this product, and we
will not say why" (S18).

> If discover and view ever disagree for the same caller, that is a bug, not a policy choice.
> `view_test.rego` asserts their decisions are identical for every sample organisation.

### S21: Bad ids

| Request | Expect |
|---|---|
| `GET /api/v1/product/abc` | `400`, from type conversion, before any policy |
| `GET /api/v1/product/-1` | `404` |
| `GET /api/v1/product/` | `404` or `405` from routing |

### S22: Viewing with policy OFF

Restart with `OPA_ENABLED=false` (§6) and repeat S17:

```bash
for id in 1 2 3 999999; do echo "$id -> $(view "$ENV_TOKEN" "$id")"; done
```

**Expect:** every real id `200` for every caller, including the ones policy would withhold, and
`404` only for ids that genuinely do not exist. Nothing is masked. Same switch, same meaning as it
has for discovery.

---

## 6. Scenarios with policy OFF

**This is a first-class supported mode, not a degraded one.** With
`application.opa.enabled=false` no decision is taken at all; the handler receives an empty
`Optional` and the search runs with nothing restricted. Discovery must work, not fail, and not
quietly return nothing.

Restart with the switch off (OPA may be stopped as well, since nothing should call it):

```bash
OPA_ENABLED=false ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

At startup you should see the documented warning that policy will **not** be evaluated and every
decision returns ALLOW.

### S12: Every caller sees the whole catalogue

```bash
for t in "$ENV_TOKEN" "$HEG_TOKEN" "$BCC_TOKEN"; do
  discover "$t" '{}' | jq '{total: .page.totalElements, count: (.products | length)}'
done
```

**Expect:** `totalElements: 17` for **all three** callers, including BCC, which discovers only 8
with policy on. Same endpoint, same tokens, different switch.

### S13: Nothing is masked and no policy block is returned

```bash
discover "$BCC_TOKEN" '{}' | jq '{policy, first: .products[0]}'
```

**Expect:**

- **`policy` is absent.** No decision was taken, so there is nothing truthful to report. It must
  not appear as `null`, and it must not appear filled in with defaults; that would be the service
  asserting a verdict nobody reached.
- Every product carries `source`, `attributes`, `organisation`, `producer`, `consumers` and
  `subscribedBy`, including for BCC, which sees almost none of that with policy on.
- Sensitive attributes are present too (`population_risk_tags`, and the organisations'
  `authorised_classifications` / `permitted_purposes`). With no policy there is nothing to
  withhold them.

### S14: Everything is filterable, including what policy would refuse

```bash
discover "$BCC_TOKEN" '{"filters":[{"attribute":"identifiability","values":["directly_identifiable"]}]}' \
  | jq '{total: .page.totalElements, names: [.products[].name]}'
```

**Expect:** `200`, with WaterAbstractionLicences, HelpToBuyApplicants, PendingPlanningApplications
and BristolTemporaryAccommodation. The identical request is a **`403`** with policy on (S16).
that contrast is the point of the scenario.

### S15: The rest of the search still works

Paging, sorting, text and malformed-request handling are the service's own and must be unchanged:

```bash
discover "$ENV_TOKEN" '{"text":"flood"}'                  | jq '[.products[].name]'
discover "$ENV_TOKEN" '{"size":5,"page":1}'               | jq .page
discover "$ENV_TOKEN" '{"size":5000}'                     | jq '.page.size'
discover "$ENV_TOKEN" '{"filters":[{"field":"name","attribute":"x","values":["v"]}]}' -i | head -1
```

**Expect:** text search behaves as in S5; paging as in S8; `size: 5000` is clamped to **100**
(`ProductSearchContract.DEFAULT_MAX_PAGE_SIZE`, the cap when nothing else sets one) rather than to a
policy cap; and a malformed filter is still a **`400`**; validation of the request shape is the
service's job, not policy's.

### S16: Switch back on and confirm the difference

Restart with `OPA_ENABLED=true` and re-run S12 and S14. The counts must return to 15 / 12 / 8, and
S14's request must become:

```json
{"status": 403, "message": "Access denied by policy",
 "reasons": ["filter.attribute_not_permitted:identifiability"], "errorId": "…"}
```

If results do **not** change when you flip the switch, something is wrong: either the switch is
not taking effect or the contract is not being applied.

---

## 7. Bad requests and refusals

A `400` and a `403` mean quite different things here, and the distinction is worth testing
deliberately: **`400` is "what you sent makes no sense", `403` is "what you asked for is not
yours to ask".**

| # | Request (as BCC, policy on) | Expect |
|---|---|---|
| R1 | `{"filters":[{"attribute":"identifiability","values":["non_personal"]}]}` | `403`, reason `filter.attribute_not_permitted:identifiability` |
| R2 | `{"filters":[{"field":"source","values":["x"]}]}` | `403`, reason `filter.field_not_permitted:source` |
| R3 | `{"filters":[{"attribute":"no_such_attribute_at_all","values":["x"]}]}` | `403`, the **same** shape of reason; an unknown name is refused exactly like a forbidden one |
| R4 | `{"filters":[{"attribute":"population_risk_tags","values":["children"]}]}` | `403`, flagged sensitive in the catalogue |
| R5 | `{"sort":[{"field":"source"}]}` | `403`, reason `sort.not_permitted:source` |
| R6 | two refused filters at once | `403` listing **both** reasons, so you can fix them in one go |
| R7 | `{"filters":[{"field":"name","attribute":"record_unit","values":["v"]}]}` | `400`, both named. Both names must be ones the caller may use, or policy refuses first (see below) |
| R8 | `{"filters":[{"values":["v"]}]}` | `400`, neither named |
| R9 | `{"filters":[{"field":"name","operator":"sounds_like","values":["v"]}]}` | `400`, unknown operator |
| R10 | `{"filters":[{"attribute":"record_unit","operator":"gt","values":["abc"]}]}` | `400`, ordering operator needs a number |
| R11 | `{"page":-1}` or `{"size":0}` | `400` |
| R12 | `{"text":"<256 characters>"}` | `400` |
| R13 | 51 filters | `400` |
| R14 | `{not-json` | `400`, "malformed JSON" |
| R15 | no body at all | **`200`**, since an empty request is valid and returns the first page of everything |

**Order matters: policy is judged before the handler validates.** The PDP sees the body exactly as
posted, so a request that is *both* malformed and names something the caller may not use comes back
`403`, not `400`, for `{"filters":[{"field":"name","attribute":"x","values":["v"]}]}` is refused with
`filter.attribute_not_permitted:x` and never reaches the check that would have called it malformed.
When writing a `400` test, name only fields and attributes the caller is permitted. Body-shape
failures (R9, R11–R14) are bound before policy runs and stay `400` whatever they name. Worked
through in
[`tests/products/product-discover.md` TC-42](tests/products/product-discover.md#tc-42--criteria-400s-decided-after-policy).

Two subtleties worth confirming by hand:

- **R3 is the anti-probing guarantee.** Compare the responses to R1 and R3 byte for byte apart from
  the name: they must be indistinguishable. If an unknown attribute gave a `400` while a forbidden
  one gave a `403`, the endpoint would become a way to enumerate the attribute vocabulary.
- **A refusal never reveals policy wiring.** No `403` body should contain a reason starting
  `policy.` or `dispatch.`; those stay in the log. Grep a few refusals to be sure.

Also worth one test each:

| Case | Expect |
|---|---|
| no `Authorization` header | `401` |
| a token without `product_discovery` | `403 "Access denied: insufficient permissions for this operation"` (the role message, not the policy one) |
| OPA stopped, policy on | `403` for every request, failing closed |
| a filter value outside the attribute's `allowed_values`, e.g. `{"attribute":"record_unit","values":["spaceship"]}` | `200` with **0 results**, not an error. Rejecting it would turn the endpoint into a probe of the vocabulary |

---

## 8. Things that should never happen

Treat any of these as a defect, whatever else is working. They are the guarantees the design rests
on, and each is cheap to check by eye:

1. **A masked member appearing anywhere.** `source`, `attributes`, `consumers` or `subscribedBy`
   present for BCC on a product it does not own (S2). Withheld data is never read from the
   database, so if it appears, the query was not built from the decision.
2. **A sensitive attribute in any response with policy on and clearance below SECRET.** Check every
   `attributes` map, at every level: product, organisation, producer, consumer, subscription:
   ```bash
   discover "$HEG_TOKEN" '{}' | jq '[.. | objects | select(has("population_risk_tags"))] | length'   # 0
   discover "$HEG_TOKEN" '{}' | grep -c authorised_classifications                                   # 0
   ```
3. **`totalElements` larger than the number of products a caller can page through.** That would
   mean the count sees products the page does not: a disclosure by counting.
4. **A product appearing for a caller who cannot discover it**, especially
   UnclassifiedDraftDataset for HEG or BCC (S9).
5. **A `500`.** Every input in §7 should produce a `400` or a `403`. A malformed body is the
   handler's `400` to give.
6. **Producer connection details** (`host`, `port`, `tls`, `idp_client_id`) or a subscription
   `destination` in any discovery response. They belong to the configuration API.
7. **A response that changes when only the page size changes.** Which products come back must be a
   function of the caller and the criteria, never of how the page is fetched.

---

## 9. Troubleshooting

| Symptom | Likely cause |
|---|---|
| every request `403 "Access denied by policy"` | OPA is down (it fails closed), or the token has no `organisation` claim → check for reason `organisation.missing` |
| `403` and the log says `policy.enforcement_missing` | policy is switched **on** but no decision reached the handler; the enforcement point did not run. Discovery refuses rather than searching unrestricted in its place; this is a wiring fault to report, not a caller problem |
| `403 "Access denied: insufficient permissions"` | the `product_discovery` role is not on the token; §2.2 |
| all three callers see the same products | see [§0](#0-if-all-three-organisations-return-the-same-products), usually the same identity being sent each time ([§3.1](#31-who-is-who)), otherwise the master switch |
| the log says `No decision published yet; supplying an empty value…` | expected, and not a failure. Arguments are resolved before the handler runs, so the enforcement point fills that value in a moment later. The line that reports the outcome is `PolicyEnforcementInterceptor … ALLOW`/`DENY` (it is at `TRACE`, so you will not normally see it at all) |
| empty results for everyone | the sample data did not load, or the organisation claim does not match any `organisation_key` (`FEDERATOR_ENV` instead of `ENV`) |
| results differ from §5 | someone changed an organisation or product attribute. Re-derive from §4 before reporting a bug; the tables there are the whole story |
| you want to see why | set `application.opa.log-input: true` and `log-output: true` (the `dev` profile does). The input shows the organisation attributes sent; the output shows the contract that came back, provenance included |
| you want to see the query | `logging.level.uk.gov.dbt.ndtp.ia.node.management.service.data.impl.ProductDiscoveryServiceImpl: DEBUG` logs the compiled SQL and its bound parameters |

To ask the PDP the same question the service asks, without the service:

```bash
curl -s localhost:8181/v1/data/dispatch/decision -d '{"input":{
  "subject":{"kind":"service","clientId":"FEDERATOR_BCC",
             "organisation":{"key":"BCC","attributes":{
               "authorised_classifications":["OFFICIAL"],
               "permitted_purposes":["service_delivery"],
               "jurisdictions":["England","Bristol"]}}},
  "action":"discover","resource":{"kind":"product"},
  "request":{"method":"POST","path":"/api/v1/product/discover","body":{}}}}' | jq .result
```

The `row_filter` that comes back is, quite literally, the `WHERE` clause of the search.

---

## Related documents

- [Policy Enforcement](POLICY_ENFORCEMENT.md): how a decision is reached and what it contains
- [Authentication Requirements](AUTHENTICATION_REQUIREMENTS.md): tokens, audiences and roles
- [Database Schema](DATABASE_SCHEMA.md): products, grants and policy attributes
- `docker/opa/policy_sample_stories.md`: the same organisations walked through at the policy level
