# API Test Requirement Specifications

**Repository:** `management-node`
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`

---

One specification per API, written for a manual testing team: what the API does, what policy
governs it, the permutations worth testing, and the edge cases that have caught real defects.

Each document follows the same shape, so a tester who has worked through one can navigate any of
them: **Objective → Preconditions → Test data → Given / When / Then**, then **Edge cases**.

## Specifications

| API | Endpoint | Specification |
|---|---|---|
| Product Discover | `POST /api/v1/product/discover` | [product-discover.md](product-discover.md) |
| Product View | `GET /api/v1/product/{productId}` | [product-view.md](product-view.md) |
| Product Subscribe | `POST /api/v1/product/subscribe` | [subscribe.md](subscribe.md) |

More will be added here as they are written, one file per API. The certificate and configuration
APIs are not yet covered.

Subscribe is the only one of the three that **writes**, and the only one that reads the caller's
jurisdiction: an organisation covering Wales may not subscribe to a `topic` product. See
[subscribe.md §4](subscribe.md#4-wales-may-not-take-topic-products).

## Read these two together

Discover and view are deliberately **one policy applied to a different number of products**. Same
caller gates, same contract, same row filter, same masking, from a single definition; the only
differences are that view returns one product by id, and returns the whole object model where a
search returns a summary.

So the most valuable cross-API check is the one neither document can make alone: **what a caller
discovers is exactly what it can view.** If those two lists ever differ, that is a defect, not a
policy choice: a product viewable but not discoverable is a disclosure, and one discoverable but
not viewable is merely confusing.

## Before you start

| | |
|---|---|
| Environment setup, tokens, and who the sample organisations are | [`docs/DISCOVERY_TEST_SCENARIOS.md`](../../DISCOVERY_TEST_SCENARIOS.md) §2 and §3 |
| Exploratory scenarios over the sample data | [`docs/DISCOVERY_TEST_SCENARIOS.md`](../../DISCOVERY_TEST_SCENARIOS.md) |
| How a policy decision is reached and what it contains | [`docs/POLICY_ENFORCEMENT.md`](../../POLICY_ENFORCEMENT.md) |
| Roles each endpoint requires | [`docs/AUTHENTICATION_REQUIREMENTS.md`](../../AUTHENTICATION_REQUIREMENTS.md) |

The scenario document is the guided tour; these are the checklists.

> **Product ids differ between environments.** They are database sequence values, so the same
> product may be id 9 in one environment and 27 in another. No expected result in these documents
> is written in terms of an id. Products are named, and where an id is needed you take it from a
> discover response.
