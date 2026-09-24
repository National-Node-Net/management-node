# METADATA
# title: Product subscription tests
# description: Story 2 of policy_sample_stories.md - the terms each organisation gets, and each refusal.
package product_subscribe_test

import data.policies.product.subscribe
import data.sample_data

cron := {"productId": 3, "scheduleType": "cron", "scheduleExpression": "0 * * * *"}

interval := {"productId": 3, "scheduleType": "interval", "scheduleExpression": "PT1H"}

post(org, body) := sample_data.input_for(org, "subscribe", "POST", body)

# The same request once the service has loaded the product the body names. Product 3
# (FloodRiskMapZones) is non_personal and validated, so it imposes no shortening.
post_with_product_3(org, body) := sample_data.candidate_input(org, "subscribe", "POST", body, "3")

# ---------------------------------------------------------------------------
# S1: the same cron request from each organisation
# ---------------------------------------------------------------------------

test_env_is_accepted_straight_away_for_90_days if {
	decision := subscribe.decision with input as post_with_product_3("ENV", cron)

	decision.allow == true
	decision.reasons == []
	decision.details == {
		"requires_approval": false,
		"max_validity_days": 90,
		"permitted_schedule_types": ["cron", "interval"],
		"validity_days": 90,
	}
}

test_heg_gets_the_longest_term_but_needs_approval if {
	decision := subscribe.decision with input as post_with_product_3("HEG", cron)

	decision.allow == true
	decision.details == {
		"requires_approval": true,
		"max_validity_days": 365,
		"permitted_schedule_types": ["cron", "interval"],
		"validity_days": 365,
	}
}

test_bcc_is_refused_cron_and_still_told_its_terms if {
	decision := subscribe.decision with input as post_with_product_3("BCC", cron)

	decision.allow == false
	decision.reasons == ["schedule.type_not_permitted"]
	decision.details == {
		"requires_approval": true,
		"max_validity_days": 30,
		"permitted_schedule_types": ["interval"],
		"validity_days": 30,
	}
}

# ---------------------------------------------------------------------------
# S2: BCC retries with an interval
# ---------------------------------------------------------------------------

test_bcc_is_allowed_an_interval_subscription if {
	decision := subscribe.decision with input as post("BCC", interval)

	decision.allow == true
	decision.details.max_validity_days == 30
}

# ---------------------------------------------------------------------------
# Other refusals
# ---------------------------------------------------------------------------

test_schedule_type_is_optional if {
	subscribe.decision.allow with input as post("BCC", {"productId": 3})
	subscribe.decision.allow with input as post("BCC", {"productId": 3, "scheduleType": null})
}

test_no_organisation_is_refused_with_every_reason if {
	decision := subscribe.decision with input as sample_data.anonymous_input("subscribe", "POST", {})

	decision.reasons == ["organisation.missing", "organisation.purpose_not_permitted", "request.product_missing"]
	decision.details.max_validity_days == 30
}

test_organisation_without_service_delivery_is_refused if {
	no_service := object.union(post("HEG", cron), {"subject": {"organisation": {"attributes": {"permitted_purposes": ["statistical_analysis"]}}}})
	decision := subscribe.decision with input as no_service

	decision.reasons == ["organisation.purpose_not_permitted"]
}

test_s2_env_and_heg_get_the_same_terms_as_s1 if {
	decision_env := subscribe.decision with input as post_with_product_3("ENV", interval)
	decision_heg := subscribe.decision with input as post_with_product_3("HEG", interval)

	decision_env.allow == true
	decision_env.details == {
		"requires_approval": false,
		"max_validity_days": 90,
		"permitted_schedule_types": ["cron", "interval"],
		"validity_days": 90,
	}
	decision_heg.allow == true
	decision_heg.details == {
		"requires_approval": true,
		"max_validity_days": 365,
		"permitted_schedule_types": ["cron", "interval"],
		"validity_days": 365,
	}
}

test_holding_both_research_and_regulatory_purposes_gives_the_longest_term if {
	both := object.union(post("ENV", interval), {"subject": {"organisation": {"attributes": {"permitted_purposes": ["regulatory_oversight", "service_delivery", "statistical_analysis"]}}}})
	decision := subscribe.decision with input as both

	decision.details.max_validity_days == 365
	decision.details.requires_approval == false
}

test_key_without_attributes_is_refused_for_purpose_only if {
	subscribe.decision.reasons == ["organisation.purpose_not_permitted"] with input as sample_data.unmatched_input("subscribe", "POST", {"productId": 3})
}

# ---------------------------------------------------------------------------
# Wales may not take topic products
#
# The product's type is not part of sample_data - it comes from product_type.name, which the
# service puts in resource.fields - so these add it to the input the service would send.
# ---------------------------------------------------------------------------

typed(request, product_type) := object.union(request, {"resource": {"fields": {"type": product_type}}})

refusal := "jurisdiction.topic_not_permitted:Wales Juristiction not allowed to access topics"

# ENV's remit is England and Wales. Holding England as well does not excuse it.
test_an_organisation_covering_wales_is_refused_a_topic_product if {
	decision := subscribe.decision with input as typed(post_with_product_3("ENV", cron), "topic")

	decision.allow == false
	decision.reasons == [refusal]
}

# The refusal is about the product's type, not the product. The same caller and the same
# product as a file is allowed.
test_the_same_caller_may_take_a_file_product if {
	decision := subscribe.decision with input as typed(post_with_product_3("ENV", cron), "file")

	decision.allow == true
	decision.reasons == []
}

# HEG covers England and Scotland, BCC Bristol and England. Neither is caught.
test_an_organisation_not_covering_wales_may_take_a_topic_product if {
	decision := subscribe.decision with input as typed(post_with_product_3("HEG", cron), "topic")

	decision.allow == true
	decision.reasons == []
}

test_a_local_remit_outside_wales_may_take_a_topic_product if {
	decision := subscribe.decision with input as typed(post_with_product_3("BCC", interval), "topic")

	decision.allow == true
	decision.reasons == []
}

# The refusal stands on its own and does not displace the others: a Welsh organisation asking
# for a schedule it may not have is told both things at once.
test_the_refusal_is_reported_alongside_every_other_reason if {
	welsh_local := object.union(
		typed(post_with_product_3("ENV", cron), "topic"),
		{"subject": {"organisation": {"attributes": {"jurisdictions": ["Bristol", "Wales"]}}}},
	)
	decision := subscribe.decision with input as welsh_local

	decision.reasons == [refusal, "schedule.type_not_permitted"]
}

# The terms are still worked out and returned, as they are for every other refusal, so a caller
# that is turned away still sees what it would have been held to.
test_the_terms_are_still_returned_with_the_refusal if {
	decision := subscribe.decision with input as typed(post_with_product_3("ENV", cron), "topic")

	decision.details == {
		"requires_approval": false,
		"max_validity_days": 90,
		"permitted_schedule_types": ["cron", "interval"],
		"validity_days": 90,
	}
}

# A product type the service did not send, or one that is not a string, is not a topic.
test_a_product_with_no_type_is_not_caught if {
	decision := subscribe.decision with input as post_with_product_3("ENV", cron)

	decision.allow == true
}

test_a_non_string_product_type_is_not_caught if {
	decision := subscribe.decision with input as typed(post_with_product_3("ENV", cron), null)

	decision.allow == true
}

# ---------------------------------------------------------------------------
# validity_days: the grant is the lower of what the caller may have and what the
# product allows. These use candidate_input, which carries the product's own
# attributes, because that is what the service sends once the body names a productId.
# ---------------------------------------------------------------------------

with_product(org, body, product_id) := sample_data.candidate_input(org, "subscribe", "POST", body, product_id)

# Product 3, FloodRiskMapZones: non_personal and validated. Nothing about the data
# shortens the grant, so the caller's own ceiling stands.
test_validity_is_the_callers_ceiling_when_the_product_does_not_shorten_it if {
	decision := subscribe.decision with input as with_product("HEG", interval, "3")

	decision.details.max_validity_days == 365
	decision.details.validity_days == 365
}

# Product 2, PendingPlanningApplications: directly identifiable. HEG may have a year
# in general, but not of this.
test_directly_identifiable_data_shortens_the_grant_to_30_days if {
	decision := subscribe.decision with input as with_product("HEG", interval, "2")

	decision.details.max_validity_days == 365
	decision.details.validity_days == 30
}

# Product 1, BrownfieldLandAvailability: pseudonymised and validated.
test_pseudonymised_data_caps_the_grant_at_90_days if {
	decision := subscribe.decision with input as with_product("HEG", interval, "1")

	decision.details.validity_days == 90
}

# The product ceiling can only shorten. ENV's purpose allows 90 days, and a product
# that would allow a year does not extend it.
test_the_product_can_only_shorten_never_extend if {
	decision := subscribe.decision with input as with_product("ENV", interval, "3")

	decision.details.max_validity_days == 90
	decision.details.validity_days == 90
}

# A product whose attributes were never recorded is treated as the most restrictive
# case: an unclassified product is not evidence that it is safe to hold for a year.
test_a_product_with_no_attributes_recorded_gets_the_shortest_grant if {
	bare := object.union(post("HEG", interval), {"resource": {"id": "999", "attributes": {}}})
	decision := subscribe.decision with input as bare

	decision.details.validity_days == 30
}

# An endpoint that names no product loads none, so the rule sees no attributes and
# applies the same restrictive default rather than failing.
test_no_product_loaded_still_yields_a_decision if {
	decision := subscribe.decision with input as post("HEG", interval)

	decision.allow == true
	decision.details.validity_days == 30
}
