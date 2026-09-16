# METADATA
# title: Product subscription
# description: |
#   Whether an organisation may subscribe to a product, and on what terms.
#
#   Allowed for any organisation that delivers a public service. The terms depend on who is
#   asking:
#     - approval   - a regulator (regulatory_oversight) is accepted straight away; everyone
#                    else waits for approval;
#     - validity   - research (statistical_analysis) needs long-running feeds: 365 days;
#                    regulatory oversight is time-boxed to an investigation: 90 days;
#                    anything else: 30 days;
#     - schedules  - an organisation with a local remit may only pull on an interval;
#                    national bodies may also use cron.
#
#   The terms travel in `details` whether or not the request is allowed, so a refused caller
#   can see what it would be held to.
package policies.product.subscribe

import data.lib.decision.deny_shape
import data.lib.decision.organisation_known
import data.lib.entitlements.has_purpose
import data.lib.entitlements.local_remit

contract := "management-node.decision/1"

version := "policies.product.subscribe/2.0.0"

decision := object.union(deny_shape, {
	"allow": allow,
	"reasons": reasons,
	"details": {
		"requires_approval": requires_approval,
		"max_validity_days": max_validity_days,
		"permitted_schedule_types": permitted_schedule_types,
	},
})

body := object.get(input, ["request", "body"], {})

# ---------------------------------------------------------------------------
# Allowed when nothing below is wrong
# ---------------------------------------------------------------------------

default allow := false

allow if count(reason_set) == 0

reasons := sort([reason | some reason in reason_set])

reason_set contains "organisation.missing" if not organisation_known

reason_set contains "organisation.purpose_not_permitted" if not has_purpose("service_delivery")

reason_set contains "request.product_missing" if object.get(body, "productId", null) in {null, ""}

reason_set contains "schedule.type_not_permitted" if {
	requested := object.get(body, "scheduleType", null)
	requested != null
	not requested in permitted_schedule_types
}

# ---------------------------------------------------------------------------
# Terms
# ---------------------------------------------------------------------------

# Regulators are accepted straight away; everyone else waits for approval.
default requires_approval := true

requires_approval := false if has_purpose("regulatory_oversight")

# The longest term any purpose the organisation holds allows.
max_validity_days := 365 if {
	has_purpose("statistical_analysis")
} else := 90 if {
	has_purpose("regulatory_oversight")
} else := 30

# A local remit pulls on an interval only.
default permitted_schedule_types := ["cron", "interval"]

permitted_schedule_types := ["interval"] if local_remit
