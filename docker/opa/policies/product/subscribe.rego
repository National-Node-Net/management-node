# METADATA
# title: Product subscription
# description: |
#   Decides whether an organisation may subscribe to a product, and on what terms. The terms
#   travel in `details` so the service applies what policy decided - approval, validity cap,
#   schedule types - instead of restating those limits in Java where they would drift.
package policies.product.subscribe

import data.lib.decision.deny_shape
import data.lib.decision.organisation_attributes
import data.lib.decision.organisation_known

contract := "management-node.decision/1"

version := "policies.product.subscribe/1.0.0"

decision := object.union(deny_shape, {
	"allow": allow,
	"reasons": reasons,
	"details": details,
})

permitted_schedule_types := ["cron", "interval"]

body := object.get(input, ["request", "body"], {})

default allow := false

allow if count(reason_set) == 0

reasons := sort([reason | some reason in reason_set])

reason_set contains "organisation.missing" if not organisation_known

reason_set contains "request.product_missing" if not product_requested

reason_set contains "schedule.type_not_permitted" if not schedule_type_permitted

default product_requested := false

product_requested if {
	product_id := object.get(body, "productId", null)
	product_id != null
	product_id != ""
}

# A schedule type is optional; the service omits unset fields, and null is treated the same
# way so a serialiser that emits nulls does not turn "not asked" into "not permitted".
default schedule_type_permitted := false

schedule_type_permitted if object.get(body, "scheduleType", null) == null

schedule_type_permitted if body.scheduleType in permitted_schedule_types

# The terms are returned whether or not the request is allowed, so a denied caller can see
# what it would be held to once the reasons are fixed.
details := {
	"requires_approval": requires_approval,
	"max_validity_days": max_validity_days,
	"permitted_schedule_types": permitted_schedule_types,
}

# Approval is skipped only on an explicit `true`: a missing, misspelt or string-typed
# attribute keeps the organisation on the approval path.
default requires_approval := true

requires_approval := false if organisation_attributes.trusted_subscriber == true

# Used when the organisation carries no numeric max_subscription_days attribute.
default max_validity_days := 30

max_validity_days := organisation_attributes.max_subscription_days if {
	is_number(organisation_attributes.max_subscription_days)
}
