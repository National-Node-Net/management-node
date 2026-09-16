# METADATA
# title: Product discovery
# description: |
#   Discovery powers a product search. This rule does not return products; it returns the
#   search contract for the caller:
#
#     - which product FIELDS (columns such as name, topic) and which policy ATTRIBUTES
#       (such as identifiability) they may filter on, and which they asked for but may not;
#     - which fields and attributes must be masked in results;
#     - row_filter: the condition over product attributes every result must satisfy;
#     - max_page_size and obligations for the search layer.
#
#   Everything is worked out from the organisation's attributes:
#     - clearance      - decides which identifiability levels are visible, whether risk-tagged
#                        products are visible, which fields are hidden and the page size;
#     - purposes       - research may search by time resolution and see experimental data;
#                        regulators see provisional data;
#     - jurisdictions  - the catalogue serves UK bodies only; a local remit adds a boundary
#                        obligation.
#
#   The same rule answers per candidate product (input.resource.id set): the product is shown
#   only when its attributes satisfy the row_filter. A product missing the attributes the filter
#   needs is refused rather than assumed safe.
package policies.product.discover

import data.lib.decision.deny_shape
import data.lib.decision.organisation_known
import data.lib.entitlements.clearance
import data.lib.entitlements.has_purpose
import data.lib.entitlements.local_remit
import data.lib.entitlements.uk_jurisdiction
import data.lib.product

contract := "management-node.decision/1"

version := "policies.product.discover/2.0.0"

decision := object.union(deny_shape, {
	"allow": allow,
	"reasons": reasons,
	"details": {
		"evaluation": evaluation,
		"allowed_filtered_fields": allowed_filtered_fields,
		"denied_filtered_fields": denied_filtered_fields,
		"masked_filtered_fields": masked_filtered_fields,
		"allowed_filtered_attributes": allowed_filtered_attributes,
		"denied_filtered_attributes": denied_filtered_attributes,
		"masked_filtered_attributes": masked_filtered_attributes,
		"row_filter": row_filter,
		"max_page_size": max_page_size,
		"obligations": obligations,
	},
})

# ---------------------------------------------------------------------------
# Allowed when nothing below is wrong
# ---------------------------------------------------------------------------

default allow := false

allow if count(reason_set) == 0

reasons := sort([reason | some reason in reason_set])

reason_set contains "organisation.missing" if not organisation_known

reason_set contains "organisation.jurisdiction_not_permitted" if not uk_jurisdiction

reason_set contains "organisation.clearance_missing" if clearance == 0

# Asking to filter on something the caller may not use refuses the search, rather than
# quietly dropping the filter and returning more than was asked for.
reason_set contains "filter.field_not_permitted" if count(denied_filtered_fields) > 0

reason_set contains "filter.attribute_not_permitted" if count(denied_filtered_attributes) > 0

reason_set contains "product.attributes_missing" if {
	evaluation == "candidate"
	some name in ["identifiability", "quality_designation"]

	# object.get rather than product_attributes[name]: a missing key must count as missing,
	# not make this rule body undefined.
	not is_string(object.get(product_attributes, name, null))
}

reason_set contains "product.identifiability_not_permitted" if {
	evaluation == "candidate"
	is_string(product_attributes.identifiability)
	not product_attributes.identifiability in permitted_identifiability
}

reason_set contains "product.quality_not_permitted" if {
	evaluation == "candidate"
	is_string(product_attributes.quality_designation)
	not product_attributes.quality_designation in permitted_quality
}

reason_set contains "product.population_risk_not_permitted" if {
	evaluation == "candidate"
	risk_tags_restricted
	some tag in tags_of(product_attributes)
	tag in risk_tags
}

# ---------------------------------------------------------------------------
# What may be searched on
# ---------------------------------------------------------------------------

# Fields: everyone may search by name, topic and type. Searching by source is limited to those
# allowed to see it, so a hidden field cannot be probed through a filter.
allowed_filtered_fields := sort(["name", "topic", "type"]) if {
	"source" in product.hidden_fields
} else := sort(["name", "source", "topic", "type"])

# Attributes: everyone may search by record unit and quality; the rest is earned.
allowed_filtered_attributes := sort([name | some name in allowed_attribute_set])

allowed_attribute_set contains name if some name in ["quality_designation", "record_unit"]

allowed_attribute_set contains "temporal_resolution" if has_purpose("statistical_analysis")

allowed_attribute_set contains "identifiability" if clearance >= 2

allowed_attribute_set contains "population_risk_tags" if clearance >= 3

# What the caller asked to filter on, split into fields and attributes by name.
requested_filters := object.keys(object.get(input, ["request", "body", "filters"], {}))

denied_filtered_fields := sort([name |
	some name in requested_filters
	name in product.fields
	not name in allowed_filtered_fields
])

denied_filtered_attributes := sort([name |
	some name in requested_filters
	not name in product.fields
	not name in allowed_filtered_attributes
])

# ---------------------------------------------------------------------------
# What must be masked in results
# ---------------------------------------------------------------------------

masked_filtered_fields := product.hidden_fields

# Values of sensitive attributes are masked for anyone not cleared to SECRET.
default masked_filtered_attributes := []

masked_filtered_attributes := product.sensitive_attributes if clearance < 3

# ---------------------------------------------------------------------------
# Which products may be returned
# ---------------------------------------------------------------------------

# Identifiability levels visible at each clearance.
identifiability_by_clearance := {
	0: [],
	1: ["anonymised", "non_personal"],
	2: ["anonymised", "non_personal", "pseudonymised"],
	3: ["anonymised", "directly_identifiable", "non_personal", "pseudonymised"],
}

permitted_identifiability := identifiability_by_clearance[min([clearance, 3])]

permitted_quality := ["experimental", "provisional", "validated"] if {
	has_purpose("statistical_analysis")
} else := ["provisional", "validated"] if {
	has_purpose("regulatory_oversight")
} else := ["validated"]

risk_tags := ["children", "domestic_abuse_survivors", "protected_witnesses", "rare_condition_cohorts"]

risk_tags_restricted if clearance < 3

# A predicate that matches nothing, used whenever the search is refused: no rows, never all rows.
default row_filter := {"type": "literal", "value": false}

row_filter := {"type": "group", "combinator": "and", "nodes": row_filter_nodes} if allow

row_filter_nodes := array.concat(
	[
		{"type": "comparison", "attribute": "identifiability", "operator": "in", "values": permitted_identifiability},
		{"type": "comparison", "attribute": "quality_designation", "operator": "in", "values": permitted_quality},
	],
	[node |
		risk_tags_restricted
		node := {"type": "comparison", "attribute": "population_risk_tags", "operator": "none_of", "values": risk_tags}
	],
)

# ---------------------------------------------------------------------------
# Limits and obligations
# ---------------------------------------------------------------------------

# Page size grows with clearance.
max_page_size := 100 if {
	clearance >= 3
} else := 50 if {
	clearance == 2
} else := 20

obligations := sort([obligation | some obligation in obligation_set])

obligation_set contains "audit_access"

obligation_set contains "mask_response" if count(masked_filtered_fields) + count(masked_filtered_attributes) > 0

obligation_set contains "aggregate_before_release" if has_purpose("statistical_analysis")

obligation_set contains "apply_boundary_filter" if local_remit

# ---------------------------------------------------------------------------
# Request or candidate
# ---------------------------------------------------------------------------

default evaluation := "request"

evaluation := "candidate" if not object.get(input, ["resource", "id"], null) in {null, ""}

product_attributes := object.get(input, ["resource", "attributes"], {})

tags_of(attributes) := tags if {
	tags := attributes.population_risk_tags
	is_array(tags)
} else := [attributes.population_risk_tags] if {
	is_string(attributes.population_risk_tags)
} else := []
