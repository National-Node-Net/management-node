# METADATA
# title: Product discovery tests
# description: Story 3 of policy_sample_stories.md - each organisation's search contract, filter refusals and per-candidate results.
package product_discover_test

import data.policies.product.discover
import data.sample_data

search(org, body) := sample_data.input_for(org, "discover", "POST", body)

candidate(org, product_id) := object.union(search(org, {"text": "land"}), {"resource": {
	"id": product_id,
	"attributes": sample_data.products[product_id],
}})

risk_tags_node := {
	"type": "comparison", "attribute": "population_risk_tags", "operator": "none_of",
	"values": ["children", "domestic_abuse_survivors", "protected_witnesses", "rare_condition_cohorts"],
}

# ---------------------------------------------------------------------------
# D1: a plain search - everyone allowed, each with a different contract
# ---------------------------------------------------------------------------

test_env_contract if {
	decision := discover.decision with input as search("ENV", {"text": "land"})

	decision.allow == true
	decision.details == {
		"evaluation": "request",
		"allowed_filtered_fields": ["name", "source", "topic", "type"],
		"denied_filtered_fields": [],
		"masked_filtered_fields": [],
		"allowed_filtered_attributes": ["identifiability", "population_risk_tags", "quality_designation", "record_unit"],
		"denied_filtered_attributes": [],
		"masked_filtered_attributes": [],
		"row_filter": {"type": "group", "combinator": "and", "nodes": [
			{"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised", "directly_identifiable", "non_personal", "pseudonymised"]},
			{"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": ["provisional", "validated"]},
		]},
		"max_page_size": 100,
		"obligations": ["audit_access"],
	}
}

test_heg_contract if {
	decision := discover.decision with input as search("HEG", {"text": "land"})

	decision.allow == true
	decision.details == {
		"evaluation": "request",
		"allowed_filtered_fields": ["name", "source", "topic", "type"],
		"denied_filtered_fields": [],
		"masked_filtered_fields": ["configurations", "consumers"],
		"allowed_filtered_attributes": ["identifiability", "quality_designation", "record_unit", "temporal_resolution"],
		"denied_filtered_attributes": [],
		"masked_filtered_attributes": ["population_risk_tags"],
		"row_filter": {"type": "group", "combinator": "and", "nodes": [
			{"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised", "non_personal", "pseudonymised"]},
			{"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": ["experimental", "provisional", "validated"]},
			risk_tags_node,
		]},
		"max_page_size": 50,
		"obligations": ["aggregate_before_release", "audit_access", "mask_response"],
	}
}

test_bcc_contract if {
	decision := discover.decision with input as search("BCC", {"text": "land"})

	decision.allow == true
	decision.details == {
		"evaluation": "request",
		"allowed_filtered_fields": ["name", "topic", "type"],
		"denied_filtered_fields": [],
		"masked_filtered_fields": ["configurations", "consumers", "policyAttributes", "source"],
		"allowed_filtered_attributes": ["quality_designation", "record_unit"],
		"denied_filtered_attributes": [],
		"masked_filtered_attributes": ["population_risk_tags"],
		"row_filter": {"type": "group", "combinator": "and", "nodes": [
			{"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised", "non_personal"]},
			{"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": ["validated"]},
			risk_tags_node,
		]},
		"max_page_size": 20,
		"obligations": ["apply_boundary_filter", "audit_access", "mask_response"],
	}
}

# ---------------------------------------------------------------------------
# D2 - D6: filters the caller may not use refuse the search
# ---------------------------------------------------------------------------

refused(org, filters, fields, attributes) if {
	decision := discover.decision with input as search(org, {"filters": filters})

	decision.allow == false
	decision.details.denied_filtered_fields == fields
	decision.details.denied_filtered_attributes == attributes
	decision.details.row_filter == {"type": "literal", "value": false}
}

allowed(org, filters) if {
	discover.decision.allow with input as search(org, {"filters": filters})
}

test_d2_identifiability_needs_official_sensitive if {
	allowed("ENV", {"identifiability": "pseudonymised"})
	allowed("HEG", {"identifiability": "pseudonymised"})
	refused("BCC", {"identifiability": "pseudonymised"}, [], ["identifiability"])
}

test_d3_each_organisation_is_refused_for_its_own_gap if {
	filters := {"population_risk_tags": "children", "temporal_resolution": "annual"}

	refused("ENV", filters, [], ["temporal_resolution"])
	refused("HEG", filters, [], ["population_risk_tags"])
	refused("BCC", filters, [], ["population_risk_tags", "temporal_resolution"])
}

test_d4_risk_tags_need_secret if {
	allowed("ENV", {"population_risk_tags": "children"})
	refused("HEG", {"population_risk_tags": "children"}, [], ["population_risk_tags"])
	refused("BCC", {"population_risk_tags": "children"}, [], ["population_risk_tags"])
}

test_d5_time_resolution_needs_a_research_purpose if {
	refused("ENV", {"temporal_resolution": "annual"}, [], ["temporal_resolution"])
	allowed("HEG", {"temporal_resolution": "annual"})
	refused("BCC", {"temporal_resolution": "annual"}, [], ["temporal_resolution"])
}

test_d6_a_hidden_field_cannot_be_searched if {
	allowed("ENV", {"source": "x", "topic": "y"})
	allowed("HEG", {"source": "x", "topic": "y"})
	refused("BCC", {"source": "x", "topic": "y"}, ["source"], [])
}

test_refusal_reasons_name_fields_and_attributes_separately if {
	decision := discover.decision with input as search("BCC", {"filters": {"source": "x", "identifiability": "y"}})

	decision.reasons == ["filter.attribute_not_permitted", "filter.field_not_permitted"]
}

# ---------------------------------------------------------------------------
# Organisation gates
# ---------------------------------------------------------------------------

test_no_organisation_is_refused if {
	decision := discover.decision with input as sample_data.anonymous_input("discover", "POST", {})

	decision.reasons == ["organisation.clearance_missing", "organisation.jurisdiction_not_permitted", "organisation.missing"]
	decision.details.max_page_size == 20
}

test_organisation_outside_the_uk_is_refused if {
	abroad := object.union(search("ENV", {}), {"subject": {"organisation": {"attributes": {"jurisdictions": ["Brittany"]}}}})

	discover.decision.reasons == ["organisation.jurisdiction_not_permitted"] with input as abroad
}

# ---------------------------------------------------------------------------
# Per candidate: the row filter applied to one product
# ---------------------------------------------------------------------------

test_env_sees_every_product if {
	discover.decision.allow with input as candidate("ENV", "1")
	discover.decision.allow with input as candidate("ENV", "2")
	discover.decision.allow with input as candidate("ENV", "3")
}

test_heg_does_not_see_the_identifiable_risk_tagged_product if {
	discover.decision.allow with input as candidate("HEG", "1")
	discover.decision.reasons == ["product.identifiability_not_permitted", "product.population_risk_not_permitted"] with input as candidate("HEG", "2")
	discover.decision.allow with input as candidate("HEG", "3")
}

test_bcc_sees_only_non_personal_validated_data if {
	discover.decision.reasons == ["product.identifiability_not_permitted"] with input as candidate("BCC", "1")
	discover.decision.reasons == ["product.identifiability_not_permitted", "product.population_risk_not_permitted", "product.quality_not_permitted"] with input as candidate("BCC", "2")
	discover.decision.allow with input as candidate("BCC", "3")
}

test_product_without_attributes_is_refused_not_assumed_safe if {
	unlabelled := object.union(search("ENV", {}), {"resource": {"id": "9", "attributes": {}}})

	discover.decision.reasons == ["product.attributes_missing"] with input as unlabelled
}

test_candidate_evaluation_is_reported if {
	discover.decision.details.evaluation == "candidate" with input as candidate("ENV", "3")
}

# ---------------------------------------------------------------------------
# Through the dispatcher
# ---------------------------------------------------------------------------

test_dispatch_resolves_discover_exactly_with_the_contract_in_details if {
	result := data.dispatch.decision with input as search("HEG", {"text": "land"})

	result.policy == {"id": "product.discover", "version": "policies.product.discover/2.0.0", "resolution": "exact"}
	result.details.masked_filtered_fields == ["configurations", "consumers"]
	not "masked_filtered_fields" in object.keys(result)
	not "masked_filtered_attributes" in object.keys(result)
}

test_non_filterable_fields_are_refused_for_everyone if {
	refused("ENV", {"id": 1, "consumers": "x"}, ["consumers", "id"], [])
	refused("HEG", {"producerId": 1}, ["producerId"], [])
}

test_unknown_filter_name_is_refused_as_an_attribute if {
	refused("ENV", {"department_id": "x"}, [], ["department_id"])
}

test_key_without_attributes_is_refused_without_organisation_missing if {
	decision := discover.decision with input as sample_data.unmatched_input("discover", "POST", {})

	decision.reasons == ["organisation.clearance_missing", "organisation.jurisdiction_not_permitted"]
}

test_top_secret_is_treated_like_secret if {
	top_secret := object.union(search("ENV", {}), {"subject": {"organisation": {"attributes": {"authorised_classifications": ["TOP SECRET"]}}}})
	decision := discover.decision with input as top_secret

	decision.details.max_page_size == 100
	decision.details.masked_filtered_fields == []
	"population_risk_tags" in decision.details.allowed_filtered_attributes
}

test_risk_tags_given_as_a_single_string_are_still_checked if {
	single := object.union(search("HEG", {}), {"resource": {"id": "9", "attributes": {
		"identifiability": "non_personal",
		"quality_designation": "validated",
		"population_risk_tags": "children",
	}}})

	discover.decision.reasons == ["product.population_risk_not_permitted"] with input as single
}
