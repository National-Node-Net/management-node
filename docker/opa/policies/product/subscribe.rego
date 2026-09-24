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
#
#   One refusal is about neither: an organisation whose remit covers Wales may not subscribe to a
#   product of type `topic` at all, however it is otherwise entitled. That is a rule about the
#   caller's remit and the product together, so it denies rather than shortening a term.
#
#   `validity_days` is the term the service records on the grant. It is the only term worked out
#   from the *product* as well as the caller: how long an organisation may hold data depends on
#   what the data is, not only on why the organisation holds data. The product reaches the rule as
#   input.resource.attributes, loaded because the body named a productId.
package policies.product.subscribe

import data.lib.decision.deny_shape
import data.lib.decision.organisation_known
import data.lib.entitlements.has_purpose
import data.lib.entitlements.jurisdictions
import data.lib.entitlements.local_remit

contract := "management-node.decision/1"

version := "policies.product.subscribe/4.0.0"

decision := object.union(deny_shape, {
	"allow": allow,
	"reasons": reasons,
	"details": {
		"requires_approval": requires_approval,
		"max_validity_days": max_validity_days,
		"permitted_schedule_types": permitted_schedule_types,
		"validity_days": validity_days,
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

# An organisation whose remit covers Wales may not take a product of type `topic`, whatever else
# it is entitled to. Holding Wales alongside other nations is enough: the remit is the reason, so
# the rest of the remit does not excuse it.
#
# The product's type reaches the rule as input.resource.fields.type, read from product_type.name
# by the service when the body names a productId. A request that names no product carries no
# type, so it is not caught here - it is already refused as request.product_missing.
#
# The reason carries its own message after the colon, in the `code:subject` form the product
# rules use for a parameterised refusal: the code before the colon stays the stable key that
# audit and logs match on, and everything after it is shown to the caller as written.
reason_set contains wales_topic_refusal if {
	"Wales" in jurisdictions
	input.resource.fields.type == "topic"
}

wales_topic_refusal := "jurisdiction.topic_not_permitted:Wales Juristiction not allowed to access topics"

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

# ---------------------------------------------------------------------------
# How long this organisation may hold this product
#
# max_validity_days above is the ceiling the caller's purpose allows, and says nothing about the
# data. validity_days is the grant actually being made, and is the lower of that ceiling and what
# the product itself permits. A caller entitled to a year of a validated, anonymised feed is
# entitled to far less of a provisional, directly identifiable one.
#
# The product's own ceiling, read downwards, first line that describes the product wins:
#
#   directly identifiable                         30 days  - re-consent and review come round fast
#   pseudonymised                                 90 days
#   experimental / provisional / superseded       30 days  - the data may still change under them
#   anything else (anonymised, non_personal,
#     validated)                                 365 days  - no product-side reason to shorten
#
# A product with no attributes recorded is treated as the most restrictive case rather than the
# least: an unclassified product is not evidence that it is safe to hold for a year.
# ---------------------------------------------------------------------------

default product_attributes := {}

product_attributes := attributes if {
	attributes := input.resource.attributes
	is_object(attributes)
}

default product_described := false

product_described if count(product_attributes) > 0

identifiability := value if {
	value := product_attributes.identifiability
	is_string(value)
} else := "unknown"

quality := value if {
	value := product_attributes.quality_designation
	is_string(value)
} else := "unknown"

# The ceiling the data itself imposes.
product_validity_days := 30 if {
	identifiability == "directly_identifiable"
} else := 30 if {
	quality in {"experimental", "provisional", "superseded"}
} else := 90 if {
	identifiability == "pseudonymised"
} else := 365 if {
	product_described
} else := 30

# The grant: never longer than the caller's purpose allows, never longer than the data allows.
validity_days := min([max_validity_days, product_validity_days])
