# METADATA
# title: Product discovery
# description: |
#   Discovery powers a product search. This rule does not return products; it returns the
#   search contract for the caller, and the search layer turns that contract into one query:
#
#     - which product FIELDS (name, topic, organisation.key) and which policy ATTRIBUTES
#       (identifiability) they may filter, sort or text-search on, and which they asked for
#       but may not;
#     - visible_fields  - what may be selected at all, and masked_filtered_fields what must be
#       left out of it; unmask_when names what is shown after all on the products matching a
#       condition, so per-product visibility needs no second decision;
#     - row_filter      - the condition every returned product must satisfy, as a predicate tree
#       in the grammar named by filter_contract. It becomes the query's WHERE clause;
#     - max_page_size and obligations for the search layer.
#
#   None of that is decided here. Who may search, on what, and what they are shown is
#   lib/product_access.rego, read by policies.product.view as well, so a product a caller cannot
#   discover is one it cannot view either, and the two endpoints cannot drift: their decisions are
#   the same document, differing only in the one key each adds.
#
#   What is this rule's own is the CANDIDATE level: the same rule answering about ONE product
#   (input.resource.id set), which is shown only when its attributes satisfy the row filter. A
#   product missing the attributes the filter needs is refused rather than assumed safe. The service
#   no longer asks per product - the database evaluates the row filter instead - but this level is
#   the oracle the row filter is tested against: discover_test.rego asserts the two agree for every
#   sample organisation and every sample product.
#
#   An organisation always discovers its own products, whatever else the contract says: the row
#   filter admits them as a first branch, unmask_when shows who is using them, and every candidate
#   rule below asks about somebody else's product only, so that the two agree.
package policies.product.discover

import data.lib.decision.deny_shape
import data.lib.decision.organisation_known
import data.lib.entitlements.jurisdictions
import data.lib.entitlements.local_remit
import data.lib.product_access as access

contract := "management-node.decision/1"

version := "policies.product.discover/3.2.0"

# The shared contract, plus the one key this rule adds: whether it was asked about a request or
# about one candidate product.
decision := object.union(deny_shape, {
	"allow": allow,
	"reasons": reasons,
	# The shared contract, plus the one thing this rule adds of its own.
	"details": object.union(access.contract(allow), {
		"evaluation": evaluation,
	}),
})

# ---------------------------------------------------------------------------
# Allowed when nothing below is wrong
# ---------------------------------------------------------------------------

default allow := false

allow if count(reason_set) == 0

reasons := sort(reason_set)

# Everything view is refused for - who reaches the catalogue at all, and criteria the caller may
# not use - together with this rule's own candidate refusals. `|` puts the two sets together.
reason_set := access.reason_set | candidate_reasons

# ---------------------------------------------------------------------------
# The candidate level: the same rule asked about ONE product
#
# Only the oracle asks this; a search lets the database apply row_filter instead. Every rule here
# judges somebody else's product, because the caller's own are always discoverable.
# ---------------------------------------------------------------------------

# Nothing is recorded about how identifiable this product's records are, or how good its data is.
# An unlabelled product is refused rather than assumed safe.
candidate_reasons contains "product.attributes_missing" if {
	somebody_elses_product
	not identifiability_recorded
}

candidate_reasons contains "product.attributes_missing" if {
	somebody_elses_product
	not quality_recorded
}

# The records identify people or businesses more closely than this caller's clearance allows.
candidate_reasons contains "product.identifiability_not_permitted" if {
	somebody_elses_product
	identifiability_recorded
	not product_attributes.identifiability in access.permitted_identifiability
}

# The data is not of a quality this caller's purposes allow - experimental data, say, for an
# organisation that does no research.
candidate_reasons contains "product.quality_not_permitted" if {
	somebody_elses_product
	quality_recorded
	not product_attributes.quality_designation in access.permitted_quality
}

# The product is tagged as covering people at risk, and this caller is not cleared to see them.
candidate_reasons contains "product.population_risk_not_permitted" if {
	somebody_elses_product
	access.risk_tags_restricted
	some tag in product_values("population_risk_tags")
	tag in access.risk_tags
}

# A caller with a local remit sees only what its own remit covers. A product that says nothing
# about where its data applies is outside every remit, so it is refused rather than assumed to
# cover everywhere.
candidate_reasons contains "product.jurisdiction_not_permitted" if {
	somebody_elses_product
	local_remit
	not covers_our_remit
}

# ---------------------------------------------------------------------------
# The product being judged
# ---------------------------------------------------------------------------

# Every candidate rule above applies to one product that is not the caller's own. A search asks
# about no product at all, so none of them applies to a search either.
default somebody_elses_product := false

somebody_elses_product if {
	candidate_product
	not own_product
}

# The service asks about one product by sending its id. A search sends none.
default candidate_product := false

candidate_product if {
	id := input.resource.id
	id != null
	id != ""
}

# The product on offer belongs to the organisation asking. The oracle supplies resource.owner; a
# search decides the same thing in SQL, through the row filter's first branch.
default own_product := false

own_product if {
	organisation_known
	input.resource.owner == input.subject.organisation.key
}

# Which question was asked, reported so the service and the log can tell a search from a
# per-product check.
default evaluation := "request"

evaluation := "candidate" if candidate_product

# ---------------------------------------------------------------------------
# Reading the product's attributes
# ---------------------------------------------------------------------------

default product_attributes := {}

product_attributes := attributes if {
	attributes := input.resource.attributes
	is_object(attributes)
}

default identifiability_recorded := false

identifiability_recorded if is_string(product_attributes.identifiability)

default quality_recorded := false

quality_recorded if is_string(product_attributes.quality_designation)

# The product says its data covers somewhere this organisation is responsible for.
default covers_our_remit := false

covers_our_remit if {
	some area in product_values("coverage_jurisdictions")
	area in jurisdictions
}

# One attribute's values as a list. A multi-valued attribute arrives as an array and a
# single-valued one as a plain string; both are read the same way here.
product_values(name) := values if {
	values := product_attributes[name]
	is_array(values)
} else := [product_attributes[name]] if {
	is_string(product_attributes[name])
} else := []
