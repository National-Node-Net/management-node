# METADATA
# title: Product subscription tests
# description: The subscription terms in `details`, and each reason a subscription is refused.
package product_subscribe_test

import data.policies.product.subscribe

request(attributes, body) := {
	"subject": {"organisation": {"key": "FEDERATOR_ENV", "attributes": attributes}},
	"resource": {"kind": "product"},
	"action": "subscribe",
	"request": {"method": "POST", "body": body},
}

# ---------------------------------------------------------------------------
# Allowed, and the terms
# ---------------------------------------------------------------------------

test_trusted_subscriber_is_allowed_without_approval_and_with_its_own_cap if {
	decision := subscribe.decision with input as request(
		{"trusted_subscriber": true, "max_subscription_days": 90},
		{"productId": 42, "scheduleType": "cron"},
	)

	decision.allow == true
	decision.reasons == []
	decision.details == {
		"requires_approval": false,
		"max_validity_days": 90,
		"permitted_schedule_types": ["cron", "interval"],
	}
}

test_organisation_without_attributes_gets_default_terms if {
	decision := subscribe.decision with input as request({}, {"productId": 42})

	decision.allow == true
	decision.details.requires_approval == true
	decision.details.max_validity_days == 30
}

test_only_boolean_true_skips_approval if {
	decision := subscribe.decision with input as request({"trusted_subscriber": "true"}, {"productId": 42})

	decision.details.requires_approval == true
}

test_non_numeric_cap_falls_back_to_default if {
	decision := subscribe.decision with input as request({"max_subscription_days": "90"}, {"productId": 42})

	decision.details.max_validity_days == 30
}

test_schedule_type_is_optional_and_null_counts_as_unset if {
	decision := subscribe.decision with input as request({}, {"productId": 42, "scheduleType": null})

	decision.allow == true
}

test_interval_schedule_is_permitted if {
	decision := subscribe.decision with input as request({}, {"productId": 42, "scheduleType": "interval"})

	decision.allow == true
}

# ---------------------------------------------------------------------------
# Deny reasons
# ---------------------------------------------------------------------------

test_missing_organisation_is_denied if {
	decision := subscribe.decision with input as {"request": {"method": "POST", "body": {"productId": 42}}}

	decision.allow == false
	decision.reasons == ["organisation.missing"]
}

test_missing_product_is_denied if {
	decision := subscribe.decision with input as request({}, {"scheduleType": "cron"})

	decision.allow == false
	decision.reasons == ["request.product_missing"]
}

test_missing_body_is_a_missing_product if {
	decision := subscribe.decision with input as {"subject": {"organisation": {"key": "FEDERATOR_ENV"}}}

	decision.allow == false
	decision.reasons == ["request.product_missing"]
}

test_unpermitted_schedule_type_is_denied if {
	decision := subscribe.decision with input as request({}, {"productId": 42, "scheduleType": "realtime"})

	decision.allow == false
	decision.reasons == ["schedule.type_not_permitted"]
}

# Terms are returned on a denial too, so the caller learns what it would be held to.
test_denied_decision_still_carries_the_terms if {
	decision := subscribe.decision with input as request({"trusted_subscriber": true}, {})

	decision.allow == false
	decision.details.requires_approval == false
}

test_every_failing_condition_is_reported_sorted if {
	decision := subscribe.decision with input as {"request": {"body": {"scheduleType": "weekly"}}}

	decision.reasons == ["organisation.missing", "request.product_missing", "schedule.type_not_permitted"]
}
