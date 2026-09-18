# METADATA
# title: Product view tests
# description: Story 1 of policy_sample_stories.md - GET /api/v1/product/{id} for each organisation.
package product_view_test

import data.policies.product.view
import data.sample_data

get(org) := sample_data.input_for(org, "view", "GET", null)

all_sensitive_fields := ["configurations", "consumers", "policyAttributes", "source"]

test_env_sees_the_full_product if {
	decision := view.decision with input as get("ENV")

	decision.allow == true
	decision.reasons == []
	decision.details == {"access_level": "full", "withheld_fields": [], "required_clearance": "OFFICIAL-SENSITIVE"}
}

test_heg_sees_a_summary_without_consumer_details if {
	decision := view.decision with input as get("HEG")

	decision.allow == true
	decision.details.access_level == "summary"
	decision.details.withheld_fields == ["configurations", "consumers"]
}

test_bcc_is_refused_for_insufficient_clearance if {
	decision := view.decision with input as get("BCC")

	decision.allow == false
	decision.reasons == ["organisation.clearance_insufficient"]
	decision.details.access_level == "none"
	decision.details.withheld_fields == all_sensitive_fields
}

test_no_organisation_is_refused_with_every_reason if {
	decision := view.decision with input as sample_data.anonymous_input("view", "GET", null)

	decision.reasons == ["organisation.clearance_insufficient", "organisation.missing"]
}

test_head_is_a_read if {
	view.decision.allow with input as sample_data.input_for("ENV", "view", "HEAD", null)
}

test_a_write_is_refused_and_shows_nothing if {
	decision := view.decision with input as sample_data.input_for("ENV", "view", "POST", null)

	decision.allow == false
	decision.reasons == ["action.not_read_only"]
	decision.details.access_level == "none"
	decision.details.withheld_fields == all_sensitive_fields
}

test_dispatch_answers_view_with_this_rule if {
	result := data.dispatch.decision with input as get("HEG")

	result.policy == {"id": "product.view", "version": "policies.product.view/1.0.0", "resolution": "exact"}
	result.details.access_level == "summary"
}

test_key_without_attributes_is_refused_without_organisation_missing if {
	view.decision.reasons == ["organisation.clearance_insufficient"] with input as sample_data.unmatched_input("view", "GET", null)
}
