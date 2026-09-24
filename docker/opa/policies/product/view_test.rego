# METADATA
# title: Product view tests
# description: |
#   Story 1 of policy_sample_stories.md - GET /api/v1/product/{id} for each organisation: the
#   gates it is refused by, and the whole contract the rule hands the service.
#
#   The tests worth insisting on are the last two groups. View's decision must be discovery's
#   decision for the same caller - the same verdict, and a details document differing only in the
#   one key each rule adds - and view's row_filter must admit exactly the products discovery's
#   admits, for every sample organisation and every sample product. Together they turn "the two
#   rules happen to agree today" into a build-time guarantee: a product a caller cannot discover
#   is one it cannot view, and a product it can discover is one it can view. The predicate
#   evaluator lives in discover_test.rego, and reads the tree the way SqlPredicateCompiler does.
package product_view_test

import data.policies.product.discover
import data.policies.product.view
import data.sample_data

get(org) := sample_data.input_for(org, "view", "GET", null)

search(org) := sample_data.input_for(org, "discover", "POST", {})

# The caller's own products, the first branch of every admitting row filter.
own_node(org) := {"type": "comparison", "field": "organisation.key", "operator": "eq", "values": [org]}

# An admitting row filter: your own products, or everything the conditions admit.
admitted(org, nodes) := {"type": "group", "combinator": "or", "nodes": [
	own_node(org),
	{"type": "group", "combinator": "and", "nodes": nodes},
]}

deny_all := {"type": "literal", "value": false}

risk_tags_node := {
	"type": "comparison", "attribute": "population_risk_tags", "operator": "none_of",
	"values": ["children", "domestic_abuse_survivors", "protected_witnesses", "rare_condition_cohorts"],
}

bristol_coverage_node := {
	"type": "comparison", "attribute": "coverage_jurisdictions", "operator": "any_of",
	"values": ["Bristol", "England"],
}

every_field := [
	"consumers", "description", "name", "organisation", "organisation.key",
	"organisation.name", "policyAttributes", "producer", "producer.active", "producer.description",
	"producer.name", "source", "subscribedBy", "topic", "type",
]

every_searchable_field := [
	"description", "name", "organisation.key", "organisation.name",
	"source", "subscribedBy", "topic", "type",
]

all_sensitive_fields := ["consumers", "policyAttributes", "source", "subscribedBy"]

unmask_own(org) := [{"names": ["subscribedBy", "consumers"], "when": own_node(org)}]

# ---------------------------------------------------------------------------
# V1: the whole contract, per organisation
#
# It is the search contract, because a view is a search returning one product. A view sends no
# body, so it asks for no filter, sort or text: the denied lists come out empty of their own
# accord, and max_page_size is inherited and ignored rather than special-cased away.
# ---------------------------------------------------------------------------

test_env_sees_the_full_product if {
	decision := view.decision with input as get("ENV")

	decision.allow == true
	decision.reasons == []
	decision.details == {
		"access_level": "full",
		"filter_contract": "management-node.filter/1",
		"allowed_filtered_fields": every_searchable_field,
		"denied_filtered_fields": [],
		"masked_filtered_fields": [],
		"allowed_filtered_attributes": [
			"identifiability", "organisation.jurisdictions", "organisation.responsibility_areas",
			"population_risk_tags", "quality_designation", "record_unit",
		],
		"denied_filtered_attributes": [],
		"masked_filtered_attributes": [],
		"mask_sensitive_attributes": false,
		"visible_fields": every_field,
		"text_search_fields": ["description", "name"],
		"unmask_when": unmask_own("ENV"),
		"row_filter": admitted("ENV", [
			{"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised", "directly_identifiable", "non_personal", "pseudonymised"]},
			{"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": ["provisional", "validated"]},
		]),
		"max_page_size": 100,
		"obligations": ["audit_access"],
	}
}

test_heg_sees_a_summary_without_consumer_details if {
	decision := view.decision with input as get("HEG")

	decision.allow == true
	decision.reasons == []
	decision.details == {
		"access_level": "summary",
		"filter_contract": "management-node.filter/1",
		"allowed_filtered_fields": every_searchable_field,
		"denied_filtered_fields": [],
		"masked_filtered_fields": ["consumers"],
		"allowed_filtered_attributes": [
			"identifiability", "organisation.jurisdictions", "organisation.responsibility_areas",
			"quality_designation", "record_unit", "temporal_resolution",
		],
		"denied_filtered_attributes": [],
		"masked_filtered_attributes": [],
		"mask_sensitive_attributes": true,
		"visible_fields": [name | some name in every_field; name != "consumers"],
		"text_search_fields": ["description", "name"],
		"unmask_when": unmask_own("HEG"),
		"row_filter": admitted("HEG", [
			{"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised", "non_personal", "pseudonymised"]},
			{"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": ["experimental", "provisional", "validated"]},
			risk_tags_node,
		]),
		"max_page_size": 50,
		"obligations": ["aggregate_before_release", "audit_access", "mask_response"],
	}
}

# BCC is OFFICIAL only. It discovers eight products, its own directly identifiable one included,
# so it views those eight and no others: what its clearance withholds is fields, not the endpoint.
test_bcc_views_what_it_discovers_with_the_sensitive_fields_withheld if {
	decision := view.decision with input as get("BCC")

	decision.allow == true
	decision.reasons == []
	decision.details == {
		"access_level": "basic",
		"filter_contract": "management-node.filter/1",
		"allowed_filtered_fields": ["description", "name", "organisation.key", "organisation.name", "topic", "type"],
		"denied_filtered_fields": [],
		"masked_filtered_fields": all_sensitive_fields,
		"allowed_filtered_attributes": [
			"organisation.jurisdictions", "organisation.responsibility_areas",
			"quality_designation", "record_unit",
		],
		"denied_filtered_attributes": [],
		"masked_filtered_attributes": [],
		"mask_sensitive_attributes": true,
		"visible_fields": [
			"description", "name", "organisation", "organisation.key", "organisation.name",
			"producer", "producer.active", "producer.description", "producer.name", "topic", "type",
		],
		"text_search_fields": ["description", "name"],
		"unmask_when": unmask_own("BCC"),
		"row_filter": admitted("BCC", [
			{"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised", "non_personal"]},
			{"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": ["validated"]},
			risk_tags_node,
			bristol_coverage_node,
		]),
		"max_page_size": 20,
		"obligations": ["audit_access", "mask_response"],
	}
}

# ---------------------------------------------------------------------------
# The gates: discovery's three, and no others
#
# A clearance floor of its own is what used to refuse BCC the products it could already discover.
# There is none now, and the tests below say so by name.
# ---------------------------------------------------------------------------

test_the_gates_are_the_ones_discovery_uses if {
	decision := view.decision with input as sample_data.anonymous_input("view", "GET", null)

	decision.allow == false
	decision.reasons == [
		"organisation.clearance_missing",
		"organisation.jurisdiction_not_permitted",
		"organisation.missing",
	]
}

test_key_without_attributes_is_refused_without_organisation_missing if {
	decision := view.decision with input as sample_data.unmatched_input("view", "GET", null)

	decision.reasons == ["organisation.clearance_missing", "organisation.jurisdiction_not_permitted"]
}

test_an_organisation_outside_the_uk_is_refused if {
	abroad := object.union(get("ENV"), {"subject": {"organisation": {"attributes": {"jurisdictions": ["Brittany"]}}}})

	view.decision.reasons == ["organisation.jurisdiction_not_permitted"] with input as abroad
}

# An OFFICIAL clearance is a clearance. Only holding none at all refuses the endpoint.
test_official_alone_is_enough_to_view if {
	view.decision.allow with input as get("BCC")

	no_clearance := object.union(get("BCC"), {"subject": {"organisation": {"attributes": {"authorised_classifications": []}}}})
	view.decision.reasons == ["organisation.clearance_missing"] with input as no_clearance
}

# Discovery is a POST and has no method gate, so neither has this rule: one here would be the last
# asymmetry between them. The endpoint is a @GetMapping, so nothing else reaches it, and unrouted
# product actions are still guarded by policies.product.fallback.
test_the_method_is_not_a_gate if {
	every method in ["GET", "HEAD", "POST", "DELETE"] {
		decision := view.decision with input as sample_data.input_for("ENV", "view", method, null)

		decision.allow == true
		decision.reasons == []
	}
}

# ---------------------------------------------------------------------------
# access_level is reported at each clearance - and nothing branches on it
# ---------------------------------------------------------------------------

test_access_level_follows_clearance if {
	view.decision.details.access_level == "full" with input as get("ENV")
	view.decision.details.access_level == "summary" with input as get("HEG")
	view.decision.details.access_level == "basic" with input as get("BCC")
	view.decision.details.access_level == "none" with input as sample_data.anonymous_input("view", "GET", null)
}

test_top_secret_is_treated_like_secret if {
	top_secret := object.union(get("ENV"), {"subject": {"organisation": {"attributes": {"authorised_classifications": ["TOP SECRET"]}}}})
	decision := view.decision with input as top_secret

	decision.details.access_level == "full"
	decision.details.masked_filtered_fields == []
	decision.details.mask_sensitive_attributes == false
}

# What each level means is said in fields, not in the word: the level and the masking lists agree,
# and it is the lists the service enforces.
test_the_access_level_only_reports_what_the_masking_lists_withhold if {
	view.decision.details.masked_filtered_fields == [] with input as get("ENV")
	view.decision.details.masked_filtered_fields == ["consumers"] with input as get("HEG")
	view.decision.details.masked_filtered_fields == all_sensitive_fields with input as get("BCC")
}

test_visible_fields_are_the_vocabulary_minus_what_is_masked if {
	every org in object.keys(sample_data.organisations) {
		decision := view.decision with input as get(org)

		decision.details.visible_fields == [name |
			some name in every_field
			not name in decision.details.masked_filtered_fields
		]

		# whose product it is, is never withheld
		"organisation" in decision.details.visible_fields
		"producer" in decision.details.visible_fields
	}
}

test_sensitivity_is_a_flag_not_a_list_of_attributes if {
	view.decision.details.mask_sensitive_attributes == false with input as get("ENV")
	view.decision.details.mask_sensitive_attributes == true with input as get("HEG")
	view.decision.details.mask_sensitive_attributes == true with input as get("BCC")

	every org in object.keys(sample_data.organisations) {
		view.decision.details.masked_filtered_attributes == [] with input as get(org)
	}
}

# ---------------------------------------------------------------------------
# Own products: shown whatever the clearance, and never anonymous
# ---------------------------------------------------------------------------

test_an_organisation_sees_who_uses_its_own_products if {
	every org in object.keys(sample_data.organisations) {
		decision := view.decision with input as get(org)

		decision.details.unmask_when == unmask_own(org)
	}

	# and for BCC those are exactly the names it has masked in general
	decision := view.decision with input as get("BCC")
	every name in decision.details.unmask_when[0].names {
		name in decision.details.masked_filtered_fields
	}
}

test_no_unmask_rule_and_nothing_visible_without_an_organisation if {
	decision := view.decision with input as sample_data.anonymous_input("view", "GET", null)

	decision.details.unmask_when == []
	decision.details.row_filter == deny_all
}

test_a_key_matching_no_organisation_row_sees_no_products if {
	decision := view.decision with input as sample_data.unmatched_input("view", "GET", null)

	decision.details.row_filter == deny_all
	decision.details.unmask_when == [{"names": ["subscribedBy", "consumers"], "when": {
		"type": "comparison", "field": "organisation.key", "operator": "eq", "values": ["FEDERATOR_ENV"],
	}}]
}

# ---------------------------------------------------------------------------
# The grammar and the obligations are ones the service knows
# ---------------------------------------------------------------------------

test_the_filter_grammar_is_declared if {
	every org in object.keys(sample_data.organisations) {
		view.decision.details.filter_contract == "management-node.filter/1" with input as get(org)
	}
}

test_every_obligation_is_one_the_service_knows if {
	every org in object.keys(sample_data.organisations) {
		decision := view.decision with input as get(org)

		# DiscoveryObligations: audit_access, mask_response, aggregate_before_release
		every obligation in decision.details.obligations {
			obligation in {"audit_access", "mask_response", "aggregate_before_release"}
		}
	}
}

test_every_named_field_is_one_the_service_knows if {
	# ProductField and ProductBlock, as lib/product.rego holds them
	every org in object.keys(sample_data.organisations) {
		decision := view.decision with input as get(org)

		every name in decision.details.visible_fields {
			name in data.lib.product.fields
		}
		every name in decision.details.allowed_filtered_fields {
			name in data.lib.product.fields
		}
		every name in decision.details.masked_filtered_fields {
			name in data.lib.product.fields
		}
	}
}

# ---------------------------------------------------------------------------
# D1: view IS discovery, for one product
#
# The two rules are one rule: the same gates, the same reasons, the same contract, all of them
# lib/product_access.rego's. The only difference is the key each adds for itself - discovery's
# `evaluation`, view's `access_level` - and this is where a new difference is caught, at build
# time, rather than by a caller refused a product it could already find.
# ---------------------------------------------------------------------------

view_only := ["access_level"]

discover_only := ["evaluation"]

agree(request) if {
	viewed := view.decision with input as request
	discovered := discover.decision with input as request

	viewed.allow == discovered.allow
	viewed.reasons == discovered.reasons
	object.remove(viewed.details, view_only) == object.remove(discovered.details, discover_only)

	# and each really does carry its own key, so the comparison is not removing nothing
	count(object.keys(viewed.details)) == 15
	count(object.keys(discovered.details)) == 15
	is_string(viewed.details.access_level)
	discovered.details.evaluation == "request"
}

test_view_and_discovery_decide_alike_for_every_organisation if {
	every org in object.keys(sample_data.organisations) {
		agree(get(org))
		agree(search(org))
	}
}

test_view_and_discovery_decide_alike_when_there_is_no_organisation if {
	agree(sample_data.anonymous_input("view", "GET", null))
	agree(sample_data.unmatched_input("view", "GET", null))
}

# Even a body only discovery would ever send is judged the same way by both, so the equality does
# not rest on view happening to send none.
test_view_and_discovery_decide_alike_on_a_body_neither_endpoint_special_cases if {
	bodies := [
		{},
		{"text": "land"},
		{"filters": [{"attribute": "identifiability", "operator": "eq", "values": ["pseudonymised"]}]},
		{"sort": [{"field": "source", "direction": "desc"}]},
	]

	every org in object.keys(sample_data.organisations) {
		every body in bodies {
			agree(sample_data.input_for(org, "view", "GET", body))
		}
	}
}

# ---------------------------------------------------------------------------
# D3: view's row filter IS discovery's
#
# The service AND-s `id eq {productId}` onto this tree, so "you can view exactly what you can
# discover" holds by construction rather than by inspection.
# ---------------------------------------------------------------------------

view_row_filter(org) := filter if {
	decision := view.decision with input as get(org)
	filter := decision.details.row_filter
}

discover_row_filter(org) := filter if {
	decision := discover.decision with input as search(org)
	filter := decision.details.row_filter
}

test_view_and_discovery_issue_the_same_predicate if {
	every org in object.keys(sample_data.organisations) {
		view_row_filter(org) == discover_row_filter(org)
	}
}

test_view_and_discovery_admit_exactly_the_same_products if {
	every org in object.keys(sample_data.organisations) {
		every _, product in sample_data.products {
			viewable := data.product_discover_test.admits(view_row_filter(org), product)
			discoverable := data.product_discover_test.admits(discover_row_filter(org), product)
			viewable == discoverable
		}
	}
}

# A refused caller is shown nothing rather than narrowly: the predicate matches no rows, exactly
# as discovery's does for the same caller.
test_a_refused_caller_gets_a_predicate_that_matches_nothing if {
	refused := sample_data.anonymous_input("view", "GET", null)

	view.decision.details.row_filter == deny_all with input as refused
	discover.decision.details.row_filter == deny_all with input as refused
}

test_bcc_views_its_own_product_and_not_one_outside_its_remit if {
	# BCC's own directly identifiable, risk-tagged product - its own, so always admitted
	data.product_discover_test.admits(view_row_filter("BCC"), sample_data.products["2"])

	# Scotland only, and BCC covers Bristol and England
	not data.product_discover_test.admits(view_row_filter("BCC"), sample_data.products["13"])
}

# ---------------------------------------------------------------------------
# Totality: a shape the service may send must never leave the decision undefined
# ---------------------------------------------------------------------------

test_a_malformed_input_is_refused_rather_than_undefined if {
	shapes := [
		{},
		{"subject": {}},
		{"subject": {"organisation": {}}},
		{"subject": {"organisation": {"key": "", "attributes": {}}}},
		{"subject": {"organisation": {"key": "ENV", "attributes": "not-an-object"}}},
		{"subject": {"organisation": {"key": "ENV", "attributes": {"authorised_classifications": 7}}}},
	]

	every shape in shapes {
		decision := view.decision with input as shape

		decision.allow == false
		decision.details.access_level == "none"
		decision.details.row_filter == deny_all
		decision.details.masked_filtered_fields == all_sensitive_fields
		count(object.keys(decision.details)) == 15
		count(decision.reasons) > 0
	}
}

# ---------------------------------------------------------------------------
# Through the dispatcher
# ---------------------------------------------------------------------------

test_dispatch_answers_view_with_this_rule if {
	result := data.dispatch.decision with input as get("HEG")

	result.policy == {"id": "product.view", "version": "policies.product.view/3.0.0", "resolution": "exact"}
	result.details.access_level == "summary"
	result.details.filter_contract == "management-node.filter/1"

	# the contract travels inside details, never as a top-level field
	not "row_filter" in object.keys(result)
	not "masked_filtered_fields" in object.keys(result)
}
