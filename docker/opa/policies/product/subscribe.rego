# METADATA
# title: Product subscription
# description: |
#   Whether an organisation may subscribe to a product, and on what terms.
#
#   Any organisation that delivers a public service may subscribe. What it is then held to -
#   whether a human has to approve it, how long it may run, and how often it may pull - depends
#   on why that organisation holds data and how wide its remit is. The three tables under
#   "The terms" are the whole of that.
#
#   The terms travel in `details` whether or not the request is allowed, so a caller that is
#   refused can still be shown what it would have been held to.
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

# The request as the caller posted it. Policy sees the body before the handler has validated it,
# so anything that is not an object is read as a request that asks for nothing.
default body := {}

body := posted if {
	posted := input.request.body
	is_object(posted)
}

# ---------------------------------------------------------------------------
# Allowed when nothing below is wrong
# ---------------------------------------------------------------------------

default allow := false

allow if count(reason_set) == 0

reasons := sort(reason_set)

# We could not name the organisation the caller acts for, so none of the terms below can be
# worked out for it.
reason_set contains "organisation.missing" if not organisation_known

# A subscription is for running a public service. An organisation that holds data only for, say,
# research has no service to deliver with it.
reason_set contains "organisation.purpose_not_permitted" if not has_purpose("service_delivery")

# A subscription has to say which product it is for.
reason_set contains "request.product_missing" if not product_named

# Naming a schedule is optional, but a schedule that is named has to be one this organisation is
# allowed - see the third table below.
reason_set contains "schedule.type_not_permitted" if {
	requested := body.scheduleType
	requested != null
	not requested in permitted_schedule_types
}

default product_named := false

product_named if {
	id := body.productId
	id != null
	id != ""
}

# ---------------------------------------------------------------------------
# The terms
#
# Three tables, each read downwards: the first line that describes the calling organisation is
# the answer, and the last line applies to everyone else.
# ---------------------------------------------------------------------------

# Does a human have to approve the subscription before it starts?
#
#   a regulator (regulatory_oversight)  no - accepted straight away
#   everyone else                       yes
requires_approval := false if {
	has_purpose("regulatory_oversight")
} else := true

# How long may a subscription run before it has to be asked for again?
#
#   research (statistical_analysis)     365 days - a study needs a long-running feed
#   a regulator (regulatory_oversight)   90 days - time-boxed to an investigation
#   everyone else                        30 days
#
# An organisation holding several purposes gets the longest term any of them allows, which is
# why research is read first.
max_validity_days := 365 if {
	has_purpose("statistical_analysis")
} else := 90 if {
	has_purpose("regulatory_oversight")
} else := 30

# How may the data be pulled?
#
#   a local remit (e.g. one city)       on a fixed interval only
#   everyone else                       a cron expression as well
permitted_schedule_types := ["interval"] if {
	local_remit
} else := ["cron", "interval"]
