# METADATA
# title: Product subscription tests
# description: Story 2 of policy_sample_stories.md - the terms each organisation gets, and each refusal.
package product_subscribe_test

import data.policies.product.subscribe
import data.sample_data

cron := {"productId": 3, "scheduleType": "cron", "scheduleExpression": "0 * * * *"}

interval := {"productId": 3, "scheduleType": "interval", "scheduleExpression": "PT1H"}

post(org, body) := sample_data.input_for(org, "subscribe", "POST", body)

# ---------------------------------------------------------------------------
# S1: the same cron request from each organisation
# ---------------------------------------------------------------------------

test_env_is_accepted_straight_away_for_90_days if {
	decision := subscribe.decision with input as post("ENV", cron)

	decision.allow == true
	decision.reasons == []
	decision.details == {"requires_approval": false, "max_validity_days": 90, "permitted_schedule_types": ["cron", "interval"]}
}

test_heg_gets_the_longest_term_but_needs_approval if {
	decision := subscribe.decision with input as post("HEG", cron)

	decision.allow == true
	decision.details == {"requires_approval": true, "max_validity_days": 365, "permitted_schedule_types": ["cron", "interval"]}
}

test_bcc_is_refused_cron_and_still_told_its_terms if {
	decision := subscribe.decision with input as post("BCC", cron)

	decision.allow == false
	decision.reasons == ["schedule.type_not_permitted"]
	decision.details == {"requires_approval": true, "max_validity_days": 30, "permitted_schedule_types": ["interval"]}
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
	decision_env := subscribe.decision with input as post("ENV", interval)
	decision_heg := subscribe.decision with input as post("HEG", interval)

	decision_env.allow == true
	decision_env.details == {"requires_approval": false, "max_validity_days": 90, "permitted_schedule_types": ["cron", "interval"]}
	decision_heg.allow == true
	decision_heg.details == {"requires_approval": true, "max_validity_days": 365, "permitted_schedule_types": ["cron", "interval"]}
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
