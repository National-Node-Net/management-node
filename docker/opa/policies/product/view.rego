# METADATA
# title: Product view
# description: |
#   Who may look at a single product, which products they may look at, and what is shown of one.
#
#   A view is a search that returns one product, so this rule IS the discovery rule: the same
#   gates, the same reasons, the same contract, all of them lib/product_access.rego's, and the
#   service AND-s the requested id onto the row filter it returns. The only difference between the
#   two decisions is the one key each adds for itself - discovery's `evaluation`, this rule's
#   `access_level` - which view_test.rego asserts for every sample organisation.
#
#   That equality is the point. "Viewable but not discoverable" would be a disclosure, and
#   "discoverable but not viewable" merely confusing, so neither is expressible: there is one
#   definition, not two that happen to agree today. It is also why this rule has no gate of its
#   own. An endpoint-specific clearance floor is exactly the kind of near-duplicate that drifts -
#   it once refused an OFFICIAL council the very products it could already discover, its own
#   included. What a clearance withholds is said where the service can act on it, in
#   masked_filtered_fields and masked_filtered_attributes.
#
#   There is no method gate either. The endpoint is a @GetMapping, so nothing else reaches this
#   rule, and discovery - a POST - has no such gate to match; unrouted product actions are still
#   guarded by policies.product.fallback, which is where a method check belongs.
#
#   The rule sees only who is asking, never which product: the service sends no product attributes
#   for this endpoint, and does not need to - the row filter decides per product in the query, and
#   the caller cannot tell a product policy excludes from one that does not exist.
package policies.product.view

import data.lib.decision.deny_shape
import data.lib.entitlements.cleared_to_official_sensitive
import data.lib.entitlements.cleared_to_secret
import data.lib.product_access as access

contract := "management-node.decision/1"

version := "policies.product.view/3.0.0"

# The shared contract, plus the one key this rule adds: how much of the product was granted.
decision := object.union(deny_shape, {
	"allow": allow,
	"reasons": reasons,
	# The shared contract, plus the one thing this rule adds of its own.
	"details": object.union(access.contract(allow), {
		"access_level": access_level,
	}),
})

# ---------------------------------------------------------------------------
# Allowed when nothing below is wrong
#
# Every reason is lib/product_access.rego's, which is what makes this rule's verdict discovery's
# verdict for the same caller.
# ---------------------------------------------------------------------------

default allow := false

allow if count(access.reason_set) == 0

reasons := sort(access.reason_set)

# ---------------------------------------------------------------------------
# How much is granted - reported, never acted on
#
# What is actually withheld is masked_filtered_fields and masked_filtered_attributes, which the
# service enforces by never selecting those columns. Two mechanisms for "show less" is how they
# come apart, so nothing branches on this value and it gates nothing: it is carried so a client
# can show the caller which tier they were granted, and so the log records it.
# ---------------------------------------------------------------------------

access_level := "full" if {
	allow
	cleared_to_secret
} else := "summary" if {
	allow
	cleared_to_official_sensitive
} else := "basic" if {
	allow
} else := "none"
