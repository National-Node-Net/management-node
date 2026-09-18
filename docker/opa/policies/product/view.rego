# METADATA
# title: Product view
# description: |
#   Who may look at a single product, and how much of it they see.
#
#   Product details reveal who consumes a product and on what terms, which is
#   OFFICIAL-SENSITIVE information. So:
#     - an organisation cleared to OFFICIAL-SENSITIVE or higher may view a product;
#     - one cleared to SECRET sees everything ("full"), one cleared to OFFICIAL-SENSITIVE
#       sees a "summary" without the consumer details;
#     - anyone else is refused.
#
#   The rule sees only who is asking, not which product: the service sends no product
#   attributes for this endpoint.
package policies.product.view

import data.lib.decision.deny_shape
import data.lib.decision.organisation_known
import data.lib.entitlements.clearance
import data.lib.product

contract := "management-node.decision/1"

version := "policies.product.view/1.0.0"

required_clearance := "OFFICIAL-SENSITIVE"

decision := object.union(deny_shape, {
	"allow": allow,
	"reasons": reasons,
	"details": {
		"access_level": access_level,
		"withheld_fields": withheld_fields,
		"required_clearance": required_clearance,
	},
})

# ---------------------------------------------------------------------------
# Allowed when nothing below is wrong
# ---------------------------------------------------------------------------

default allow := false

allow if count(reason_set) == 0

reasons := sort([reason | some reason in reason_set])

reason_set contains "organisation.missing" if not organisation_known

reason_set contains "organisation.clearance_insufficient" if clearance < 2

reason_set contains "action.not_read_only" if not input.request.method in {"GET", "HEAD"}

# ---------------------------------------------------------------------------
# How much is shown
# ---------------------------------------------------------------------------

access_level := "full" if {
	allow
	clearance >= 3
} else := "summary" if {
	allow
} else := "none"

# A refused caller is shown nothing, so every sensitive field is withheld.
withheld_fields := product.hidden_fields if {
	allow
} else := ["configurations", "consumers", "policyAttributes", "source"]
