# METADATA
# title: Product discovery tests
# description: |
#   Story 3 of policy_sample_stories.md - each organisation's search contract, its named filter and
#   sort refusals, what it may see, and which products it discovers.
#
#   The most valuable test here is the last one: the row filter the rule hands the database and the
#   rule's own per-candidate verdict must agree for every sample organisation and every sample
#   product. The service no longer asks per product, so this agreement is what keeps the query
#   honest. It is checked with a small evaluator of the predicate grammar, which does to the tree
#   what the SQL compiler does.
package product_discover_test

import data.policies.product.discover
import data.sample_data

search(org, body) := sample_data.input_for(org, "discover", "POST", body)

candidate(org, product_id) := sample_data.candidate_input(org, "discover", "POST", {}, product_id)

# The caller's own products, the first branch of every allowed row filter.
own_node(org) := {"type": "comparison", "field": "organisation.key", "operator": "eq", "values": [org]}

# An allowed row filter: your own products, or everything the conditions admit.
admitted(org, nodes) := {"type": "group", "combinator": "or", "nodes": [
	own_node(org),
	{"type": "group", "combinator": "and", "nodes": nodes},
]}

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

# ---------------------------------------------------------------------------
# D1: a plain search - everyone allowed, each with a different contract
# ---------------------------------------------------------------------------

test_env_contract if {
	decision := discover.decision with input as search("ENV", {"text": "land"})

	decision.allow == true
	decision.details == {
		"evaluation": "request",
		"filter_contract": "management-node.filter/1",
		"allowed_filtered_fields": ["description", "name", "organisation.key", "organisation.name", "source", "subscribedBy", "topic", "type"],
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
		"unmask_when": [{"names": ["subscribedBy", "consumers"], "when": own_node("ENV")}],
		"row_filter": admitted("ENV", [
			{"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised", "directly_identifiable", "non_personal", "pseudonymised"]},
			{"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": ["provisional", "validated"]},
		]),
		"max_page_size": 100,
		"obligations": ["audit_access"],
	}
}

test_heg_contract if {
	decision := discover.decision with input as search("HEG", {"text": "land"})

	decision.allow == true
	decision.details == {
		"evaluation": "request",
		"filter_contract": "management-node.filter/1",
		"allowed_filtered_fields": ["description", "name", "organisation.key", "organisation.name", "source", "subscribedBy", "topic", "type"],
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
		"unmask_when": [{"names": ["subscribedBy", "consumers"], "when": own_node("HEG")}],
		"row_filter": admitted("HEG", [
			{"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised", "non_personal", "pseudonymised"]},
			{"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": ["experimental", "provisional", "validated"]},
			risk_tags_node,
		]),
		"max_page_size": 50,
		"obligations": ["aggregate_before_release", "audit_access", "mask_response"],
	}
}

test_bcc_contract if {
	decision := discover.decision with input as search("BCC", {"text": "land"})

	decision.allow == true
	decision.details == {
		"evaluation": "request",
		"filter_contract": "management-node.filter/1",
		"allowed_filtered_fields": ["description", "name", "organisation.key", "organisation.name", "topic", "type"],
		"denied_filtered_fields": [],
		"masked_filtered_fields": ["consumers", "policyAttributes", "source", "subscribedBy"],
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
		"unmask_when": [{"names": ["subscribedBy", "consumers"], "when": own_node("BCC")}],
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

test_the_filter_grammar_is_declared_so_java_can_refuse_one_it_does_not_know if {
	discover.decision.details.filter_contract == "management-node.filter/1" with input as search("BCC", {})
}

# ---------------------------------------------------------------------------
# D2 - D6: criteria the caller may not use refuse the search, and are named
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

attribute_filter(name, value) := {"attribute": name, "operator": "eq", "values": [value]}

field_filter(name, value) := {"field": name, "operator": "eq", "values": [value]}

test_d2_identifiability_needs_official_sensitive if {
	allowed("ENV", [attribute_filter("identifiability", "pseudonymised")])
	allowed("HEG", [attribute_filter("identifiability", "pseudonymised")])
	refused("BCC", [attribute_filter("identifiability", "pseudonymised")], [], ["identifiability"])
}

test_d3_each_organisation_is_refused_for_its_own_gap if {
	filters := [
		attribute_filter("population_risk_tags", "children"),
		attribute_filter("temporal_resolution", "annual"),
	]

	refused("ENV", filters, [], ["temporal_resolution"])
	refused("HEG", filters, [], ["population_risk_tags"])
	refused("BCC", filters, [], ["population_risk_tags", "temporal_resolution"])
}

test_d4_risk_tags_need_secret if {
	allowed("ENV", [attribute_filter("population_risk_tags", "children")])
	refused("HEG", [attribute_filter("population_risk_tags", "children")], [], ["population_risk_tags"])
	refused("BCC", [attribute_filter("population_risk_tags", "children")], [], ["population_risk_tags"])
}

test_d5_time_resolution_needs_a_research_purpose if {
	refused("ENV", [attribute_filter("temporal_resolution", "annual")], [], ["temporal_resolution"])
	allowed("HEG", [attribute_filter("temporal_resolution", "annual")])
	refused("BCC", [attribute_filter("temporal_resolution", "annual")], [], ["temporal_resolution"])
}

test_d6_a_hidden_field_cannot_be_searched if {
	allowed("ENV", [field_filter("source", "x"), field_filter("topic", "y")])
	allowed("HEG", [field_filter("source", "x"), field_filter("topic", "y")])
	refused("BCC", [field_filter("source", "x"), field_filter("topic", "y")], ["source"], [])
}

test_who_uses_a_product_can_only_be_searched_by_those_who_may_see_it if {
	allowed("ENV", [{"field": "subscribedBy", "operator": "any_of", "values": ["BCC"]}])
	allowed("HEG", [{"field": "subscribedBy", "operator": "any_of", "values": ["BCC"]}])
	refused("BCC", [{"field": "subscribedBy", "operator": "any_of", "values": ["ENV"]}], ["subscribedBy"], [])
}

test_a_refusal_names_the_field_and_the_attribute_it_refuses if {
	decision := discover.decision with input as search("BCC", {"filters": [
		field_filter("source", "x"),
		attribute_filter("identifiability", "pseudonymised"),
	]})

	decision.reasons == [
		"filter.attribute_not_permitted:identifiability",
		"filter.field_not_permitted:source",
	]
}

test_every_denied_name_gets_its_own_reason if {
	decision := discover.decision with input as search("BCC", {"filters": [
		attribute_filter("identifiability", "x"),
		attribute_filter("temporal_resolution", "annual"),
	]})

	decision.reasons == [
		"filter.attribute_not_permitted:identifiability",
		"filter.attribute_not_permitted:temporal_resolution",
	]
}

# ---------------------------------------------------------------------------
# Scopes: a name outside the product is qualified with the entity it belongs to
# ---------------------------------------------------------------------------

test_the_offering_organisation_can_be_searched_by_everyone if {
	filters := [
		{"scope": "organisation", "field": "key", "operator": "in", "values": ["ENV"]},
		{"scope": "organisation", "attribute": "responsibility_areas", "operator": "any_of", "values": ["flood_risk_management"]},
	]

	allowed("ENV", filters)
	allowed("HEG", filters)
	allowed("BCC", filters)
}

test_an_organisations_entitlement_attributes_are_refused_by_their_qualified_name if {
	decision := discover.decision with input as search("ENV", {"filters": [
		{"scope": "organisation", "attribute": "authorised_classifications", "operator": "any_of", "values": ["SECRET"]},
	]})

	decision.reasons == ["filter.attribute_not_permitted:organisation.authorised_classifications"]
	decision.details.denied_filtered_attributes == ["organisation.authorised_classifications"]
}

test_a_product_scope_is_written_bare if {
	decision := discover.decision with input as search("ENV", {"filters": [
		{"scope": "product", "attribute": "record_unit", "operator": "eq", "values": ["property"]},
	]})

	decision.allow == true
}

# ---------------------------------------------------------------------------
# Sorting is judged like filtering, and named as a sort
# ---------------------------------------------------------------------------

test_a_denied_sort_field_refuses_the_search if {
	decision := discover.decision with input as search("BCC", {"sort": [
		{"field": "name", "direction": "asc"},
		{"field": "source", "direction": "desc"},
	]})

	decision.allow == false
	decision.reasons == ["sort.not_permitted:source"]
	decision.details.denied_filtered_fields == ["source"]
}

test_a_denied_sort_attribute_refuses_the_search if {
	decision := discover.decision with input as search("ENV", {"sort": [{"attribute": "temporal_resolution"}]})

	decision.allow == false
	decision.reasons == ["sort.not_permitted:temporal_resolution"]
	decision.details.denied_filtered_attributes == ["temporal_resolution"]
}

test_an_allowed_sort_key_is_not_refused if {
	discover.decision.allow with input as search("BCC", {"sort": [
		{"field": "name"},
		{"scope": "organisation", "field": "key", "direction": "desc"},
		{"attribute": "record_unit"},
	]})
}

# ---------------------------------------------------------------------------
# Free text searches the name and the description
# ---------------------------------------------------------------------------

test_text_searches_the_name_and_the_description if {
	every org in object.keys(sample_data.organisations) {
		discover.decision.details.text_search_fields == ["description", "name"] with input as search(org, {"text": "flood"})
	}
}

test_text_counts_as_asking_to_search_the_description if {
	decision := discover.decision with input as search("BCC", {"text": "flood"})
		with data.lib.product.hidden_fields as ["description"]

	decision.allow == false
	decision.details.denied_filtered_fields == ["description"]
	"filter.field_not_permitted:description" in decision.reasons
	decision.details.text_search_fields == ["name"]
}

test_text_is_refused_when_no_column_is_left_to_search if {
	decision := discover.decision with input as search("ENV", {"text": "flood"})
		with data.lib.product.hidden_fields as ["description", "name"]

	decision.allow == false
	decision.details.text_search_fields == []
	"text.not_permitted" in decision.reasons
}

test_an_empty_text_asks_for_nothing if {
	decision := discover.decision with input as search("BCC", {"text": ""})

	decision.allow == true
	decision.details.denied_filtered_fields == []
}

# ---------------------------------------------------------------------------
# What may be selected at all
# ---------------------------------------------------------------------------

test_visible_fields_are_the_vocabulary_minus_what_is_hidden if {
	discover.decision.details.visible_fields == every_field with input as search("ENV", {})

	heg := [name | some name in every_field; name != "consumers"]
	discover.decision.details.visible_fields == heg with input as search("HEG", {})

	bcc := [name |
		some name in every_field
		not name in ["consumers", "policyAttributes", "source", "subscribedBy"]
	]
	discover.decision.details.visible_fields == bcc with input as search("BCC", {})
}

test_everyone_sees_whose_product_it_is if {
	every org in object.keys(sample_data.organisations) {
		decision := discover.decision with input as search(org, {})

		"organisation" in decision.details.visible_fields
		"producer" in decision.details.visible_fields
		"organisation.key" in decision.details.allowed_filtered_fields
		"organisation.name" in decision.details.allowed_filtered_fields
		not "organisation" in decision.details.masked_filtered_fields
		not "producer" in decision.details.masked_filtered_fields
	}
}

test_sensitivity_is_a_flag_not_a_list_of_attributes if {
	discover.decision.details.mask_sensitive_attributes == false with input as search("ENV", {})
	discover.decision.details.mask_sensitive_attributes == true with input as search("HEG", {})
	discover.decision.details.mask_sensitive_attributes == true with input as search("BCC", {})

	every org in object.keys(sample_data.organisations) {
		discover.decision.details.masked_filtered_attributes == [] with input as search(org, {})
	}
}

test_masking_is_obliged_when_anything_is_withheld if {
	discover.decision.details.obligations == ["audit_access"] with input as search("ENV", {})
	"mask_response" in discover.decision.details.obligations with input as search("HEG", {})
	"mask_response" in discover.decision.details.obligations with input as search("BCC", {})
}

# ---------------------------------------------------------------------------
# Own products: always discoverable, and never anonymous
# ---------------------------------------------------------------------------

test_the_row_filter_admits_your_own_products_first if {
	every org in object.keys(sample_data.organisations) {
		decision := discover.decision with input as search(org, {})

		decision.details.row_filter.combinator == "or"
		decision.details.row_filter.nodes[0] == own_node(org)
	}
}

test_own_products_are_discoverable_whatever_else_the_contract_says if {
	# BCC's own product is directly identifiable, provisional and risk-tagged; ENV's own draft
	# dataset has neither identifiability nor quality; HEG's own register is superseded.
	discover.decision.allow with input as candidate("BCC", "2")
	discover.decision.allow with input as candidate("BCC", "16")
	discover.decision.allow with input as candidate("ENV", "8")
	discover.decision.allow with input as candidate("HEG", "12")
}

test_an_organisation_sees_who_uses_its_own_products if {
	decision := discover.decision with input as search("BCC", {})

	decision.details.unmask_when == [{
		"names": ["subscribedBy", "consumers"],
		"when": own_node("BCC"),
	}]

	# and those are exactly the names BCC has masked in general
	every name in decision.details.unmask_when[0].names {
		name in decision.details.masked_filtered_fields
	}
}

test_no_unmask_rule_without_an_organisation if {
	decision := discover.decision with input as sample_data.anonymous_input("discover", "POST", {})

	decision.details.unmask_when == []
	decision.details.row_filter == {"type": "literal", "value": false}
}

# ---------------------------------------------------------------------------
# The remit boundary is a condition on the data, not a duty on the search layer
# ---------------------------------------------------------------------------

test_a_local_remit_gets_a_coverage_condition if {
	decision := discover.decision with input as search("BCC", {})

	bristol_coverage_node in decision.details.row_filter.nodes[1].nodes
	not "apply_boundary_filter" in decision.details.obligations
}

test_a_national_remit_gets_no_coverage_condition if {
	every org in ["ENV", "HEG"] {
		decision := discover.decision with input as search(org, {})

		every node in decision.details.row_filter.nodes[1].nodes {
			node.attribute != "coverage_jurisdictions"
		}
		not "apply_boundary_filter" in decision.details.obligations
	}
}

test_a_product_outside_a_local_remit_is_refused if {
	# Scotland only, and BCC covers Bristol and England.
	decision := discover.decision with input as candidate("BCC", "13")

	decision.allow == false
	decision.reasons == ["product.jurisdiction_not_permitted"]
}

test_a_product_with_no_coverage_is_outside_every_local_remit if {
	uncovered := object.union(search("BCC", {}), {"resource": {
		"id": "99",
		"owner": "ENV",
		"attributes": {"identifiability": "non_personal", "quality_designation": "validated"},
	}})

	discover.decision.reasons == ["product.jurisdiction_not_permitted"] with input as uncovered
}

# ---------------------------------------------------------------------------
# A body the handler will refuse must not make the rule undefined
# ---------------------------------------------------------------------------

test_a_malformed_body_asks_for_nothing if {
	shapes := [
		{"filters": "not-a-list"},
		{"filters": {"identifiability": "pseudonymised"}},
		{"filters": ["just-a-string"]},
		{"filters": [{"values": ["x"]}]},
		{"filters": [{"field": 42, "values": ["x"]}]},
		{"filters": [{"attribute": null}]},
		{"sort": {"field": "source"}},
		{"sort": ["source"]},
		{"text": 42},
		"not-an-object",
		null,
	]

	every body in shapes {
		decision := discover.decision with input as search("BCC", body)

		decision.allow == true
		decision.details.denied_filtered_fields == []
		decision.details.denied_filtered_attributes == []
	}
}

test_a_malformed_scope_falls_back_to_the_bare_name if {
	decision := discover.decision with input as search("ENV", {"filters": [
		{"scope": 7, "attribute": "record_unit", "values": ["property"]},
	]})

	decision.allow == true
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

test_key_without_attributes_is_refused_without_organisation_missing if {
	decision := discover.decision with input as sample_data.unmatched_input("discover", "POST", {})

	decision.reasons == ["organisation.clearance_missing", "organisation.jurisdiction_not_permitted"]
}

test_top_secret_is_treated_like_secret if {
	top_secret := object.union(search("ENV", {}), {"subject": {"organisation": {"attributes": {"authorised_classifications": ["TOP SECRET"]}}}})
	decision := discover.decision with input as top_secret

	decision.details.max_page_size == 100
	decision.details.masked_filtered_fields == []
	decision.details.mask_sensitive_attributes == false
	"population_risk_tags" in decision.details.allowed_filtered_attributes
}

# ---------------------------------------------------------------------------
# Per candidate: the same rule judging one product
# ---------------------------------------------------------------------------

test_env_sees_other_organisations_products if {
	discover.decision.allow with input as candidate("ENV", "1")
	discover.decision.allow with input as candidate("ENV", "2")
	discover.decision.allow with input as candidate("ENV", "14")
}

test_heg_does_not_see_the_identifiable_risk_tagged_product if {
	discover.decision.allow with input as candidate("HEG", "3")
	discover.decision.reasons == ["product.identifiability_not_permitted", "product.population_risk_not_permitted"] with input as candidate("HEG", "2")
}

test_bcc_sees_only_non_personal_validated_data_in_its_own_area if {
	discover.decision.reasons == ["product.identifiability_not_permitted"] with input as candidate("BCC", "1")
	discover.decision.allow with input as candidate("BCC", "3")
	discover.decision.reasons == ["product.quality_not_permitted"] with input as candidate("BCC", "5")
}

test_product_without_attributes_is_refused_not_assumed_safe if {
	discover.decision.reasons == ["product.attributes_missing"] with input as candidate("HEG", "8")
}

test_candidate_evaluation_is_reported if {
	discover.decision.details.evaluation == "candidate" with input as candidate("ENV", "1")
}

test_risk_tags_given_as_a_single_string_are_still_checked if {
	single := object.union(candidate("HEG", "3"), {"resource": {"attributes": {
		"coverage_jurisdictions": "England",
		"identifiability": "non_personal",
		"quality_designation": "validated",
		"population_risk_tags": "children",
	}}})

	discover.decision.reasons == ["product.population_risk_not_permitted"] with input as single
}

# ---------------------------------------------------------------------------
# The row filter and the candidate level must decide alike
#
# The service asks for one decision per search and lets the database apply row_filter, so nothing
# re-checks a product afterwards. These two therefore have to agree, and this is where a
# divergence is caught - at build time, not per request.
# ---------------------------------------------------------------------------

test_the_row_filter_admits_exactly_the_products_the_candidate_level_allows if {
	every org in object.keys(sample_data.organisations) {
		every id, _ in sample_data.products {
			agree(org, id)
		}
	}
}

agree(org, id) if {
	contract := discover.decision with input as search(org, {})
	verdict := discover.decision with input as candidate(org, id)

	verdict.allow == admits(contract.details.row_filter, sample_data.products[id])
}

test_the_evaluator_reads_the_grammar_the_way_the_database_does if {
	product := sample_data.products["2"]

	admits({"type": "literal", "value": true}, product)
	not admits({"type": "literal", "value": false}, product)
	admits(admitted("BCC", [{"type": "literal", "value": false}]), product)
	not admits(admitted("ENV", [{"type": "literal", "value": false}]), product)
	not admits(admitted("ENV", [risk_tags_node]), product)
	admits(admitted("ENV", [risk_tags_node]), sample_data.products["1"])

	# a positive operator needs the attribute; a negative one holds without it
	unrecorded_in := {"type": "comparison", "attribute": "nothing_recorded", "operator": "in", "values": ["x"]}
	unrecorded_none_of := {"type": "comparison", "attribute": "nothing_recorded", "operator": "none_of", "values": ["x"]}

	not admits(admitted("ENV", [unrecorded_in]), product)
	admits(admitted("ENV", [unrecorded_none_of]), product)
}

# A reading of management-node.filter/1, so a test can ask what a row filter admits. It does to
# the tree what SqlPredicateCompiler does, including the missing-attribute semantics of the
# specification: a positive operator needs the attribute to be present, a negative one holds
# without it.
#
# Rego forbids recursion, so this is not a general evaluator: it reads the two levels this rule
# builds - an `or` of the caller's own products and an `and` of conditions - and refuses anything
# else, which is itself worth asserting.
default admits(_, _) := false

admits(node, _) if {
	node.type == "literal"
	node.value == true
}

admits(node, product) if {
	node.type == "group"
	node.combinator == "or"
	some branch in node.nodes
	branch_holds(branch, product)
}

default branch_holds(_, _) := false

branch_holds(node, product) if {
	node.type == "group"
	node.combinator == "and"
	every condition in node.nodes {
		holds(condition, product)
	}
}

branch_holds(node, product) if holds(node, product)

default holds(_, _) := false

holds(node, _) if {
	node.type == "literal"
	node.value == true
}

holds(node, product) if {
	node.type == "comparison"
	node.field == "organisation.key"
	node.operator == "eq"
	product.owner == node.values[0]
}

holds(node, product) if {
	node.type == "comparison"
	node.operator in {"any_of", "in"}
	some value in attribute_values(product, node.attribute)
	value in node.values
}

holds(node, product) if {
	node.type == "comparison"
	node.operator == "none_of"
	count([value |
		some value in attribute_values(product, node.attribute)
		value in node.values
	]) == 0
}

attribute_values(product, name) := values if {
	values := product.attributes[name]
	is_array(values)
} else := [product.attributes[name]] if {
	is_string(product.attributes[name])
} else := []

# ---------------------------------------------------------------------------
# Through the dispatcher
# ---------------------------------------------------------------------------

test_dispatch_resolves_discover_exactly_with_the_contract_in_details if {
	result := data.dispatch.decision with input as search("HEG", {"text": "land"})

	result.policy == {"id": "product.discover", "version": "policies.product.discover/3.2.0", "resolution": "exact"}
	result.details.masked_filtered_fields == ["consumers"]
	result.details.filter_contract == "management-node.filter/1"
	not "masked_filtered_fields" in object.keys(result)
	not "row_filter" in object.keys(result)
}
