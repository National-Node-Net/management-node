# METADATA
# title: Product access
# description: |
#   One definition of what a caller may do with products, and of the document that says so.
#
#   lib/product.rego is the vocabulary: what a product HAS, and which of it a clearance hides.
#   This module is the policy: whether THIS caller reaches the catalogue at all, what it may search
#   on, which products exist for it and what is shown of each - worked out from the calling
#   organisation's attributes alone.
#
#   Both policies.product.discover and policies.product.view are this module. Each keeps only what
#   is genuinely its own - discovery its per-candidate oracle, view its reported access_level - and
#   takes everything else from here: the same gates, the same reasons, the same contract. "You may
#   view exactly what you may discover" is therefore one definition rather than two that happen to
#   agree today, and a change to either endpoint's terms is a change to this file.
#
#   Read it in the order it is written:
#
#     1. The document both rules return - contract(allowed), every key listed in one place.
#     2. Why a request is refused - gate_reasons (who reaches the catalogue at all) and
#        criteria_reasons (what the caller asked to filter, sort or text-search on and may not).
#     3. What the caller asked for - read from input.request.body, so a request with no body
#        (a view) asks for nothing and is refused nothing.
#     4. What the caller may ask for - the searchable fields and attributes it has earned.
#     5. What is shown of each product - what may be selected, what is masked, what is unmasked
#        after all.
#     6. Which products the caller may see - the row filter, and the conditions it is built from.
#     7. Limits and duties - page size and obligations.
#
#   An organisation always sees its own products, whatever the conditions say: the row filter
#   admits them as a first branch, and unmask_when shows who is using them.
#
#   The wire names here are the service's: every field is a ProductField or ProductBlock, every
#   operator a ComparisonOperator, every obligation one DiscoveryObligations knows. A name outside
#   those makes the decision unreadable, which is a DENY.
package lib.product_access

import data.lib.decision.organisation_known
import data.lib.entitlements.cleared_to_official
import data.lib.entitlements.cleared_to_official_sensitive
import data.lib.entitlements.cleared_to_secret
import data.lib.entitlements.has_purpose
import data.lib.entitlements.jurisdictions
import data.lib.entitlements.local_remit
import data.lib.entitlements.uk_jurisdiction
import data.lib.product

# The grammar row_filter and unmask_when[].when are written in. The service refuses a decision
# whose grammar it does not know rather than guessing at the tree's meaning. Each rule declares it
# in its own right, from here, so the two can never name different grammars.
filter_contract := "management-node.filter/1"

# ---------------------------------------------------------------------------
# 1. The document both rules return
#
# Every key of the details document except the one each rule adds for itself: `evaluation` for
# discovery, `access_level` for view. `allowed` is the rule's own verdict, because a refused
# request returns no rows at all - see row_filter below.
# ---------------------------------------------------------------------------

contract(allowed) := {
	"filter_contract": filter_contract,
	"allowed_filtered_fields": allowed_filtered_fields,
	"denied_filtered_fields": denied_filtered_fields,
	"masked_filtered_fields": masked_filtered_fields,
	"allowed_filtered_attributes": allowed_filtered_attributes,
	"denied_filtered_attributes": denied_filtered_attributes,
	"masked_filtered_attributes": masked_filtered_attributes,
	"mask_sensitive_attributes": mask_sensitive_attributes,
	"visible_fields": visible_fields,
	"text_search_fields": text_search_fields,
	"unmask_when": unmask_when,
	"row_filter": row_filter(allowed),
	"max_page_size": max_page_size,
	"obligations": obligations,
}

# ---------------------------------------------------------------------------
# 2. Why a request is refused
#
# Both rules refuse on exactly these, so a caller that may discover a product may view it. A rule
# with its own extra condition - discovery's per-candidate level - adds to this set rather than
# replacing it.
# ---------------------------------------------------------------------------

reason_set := gate_reasons | criteria_reasons

# The caller-level gate: who reaches the catalogue at all. Three questions, each answered by the
# organisation's own attributes, and nothing further matters if any of them fails.

# We could not name the calling organisation, so we know nothing about it.
gate_reasons contains "organisation.missing" if not organisation_known

# The catalogue serves UK bodies. An organisation with no UK nation in its remit is not one.
gate_reasons contains "organisation.jurisdiction_not_permitted" if not uk_jurisdiction

# An organisation holding no classification at all is cleared for nothing, not even OFFICIAL.
gate_reasons contains "organisation.clearance_missing" if not cleared_to_official

# Asking to filter, sort or text-search on something the caller may not use refuses the request,
# rather than quietly dropping the criterion and returning more than was asked for. Each refusal
# names what was refused, so a caller fixes every one of them at once; the name is the caller's
# own, and one that does not exist is refused exactly like one that is forbidden.
criteria_reasons contains sprintf("filter.field_not_permitted:%s", [name]) if {
	some name in requested_filter_fields
	not name in allowed_filtered_fields
}

criteria_reasons contains sprintf("filter.attribute_not_permitted:%s", [name]) if {
	some name in requested_filter_attributes
	not name in allowed_filtered_attributes
}

criteria_reasons contains sprintf("sort.not_permitted:%s", [name]) if {
	some name in requested_sort_fields
	not name in allowed_filtered_fields
}

criteria_reasons contains sprintf("sort.not_permitted:%s", [name]) if {
	some name in requested_sort_attributes
	not name in allowed_filtered_attributes
}

# Free text runs against the columns in text_search_fields. With none of them left there is
# nothing to search, and answering an empty result would look like "no such product".
criteria_reasons contains "text.not_permitted" if {
	text_requested
	count(text_search_fields) == 0
}

# ---------------------------------------------------------------------------
# 3. What the caller asked for
#
# The Policy Enforcement Point sends the body as posted, before the handler has validated it, so
# every rule here tolerates a shape the handler will later refuse with 400: a `filters` that is
# not a list, or an entry naming no string field or attribute, simply asks for nothing. A request
# with no body at all - a view - asks for nothing in the same way.
# ---------------------------------------------------------------------------

default body := {}

body := input.request.body if is_object(input.request.body)

# The criteria the caller listed. Anything that is not a list of objects asks for nothing.
default filter_entries := []

filter_entries := [entry |
	some entry in body.filters
	is_object(entry)
] if is_array(body.filters)

default sort_entries := []

sort_entries := [entry |
	some entry in body.sort
	is_object(entry)
] if is_array(body.sort)

# A criterion names its target with `field` or `attribute`, and the entity that target belongs to
# with `scope`. The contract's lists use one string for both, so a scope other than the product
# becomes a prefix: {"scope": "organisation", "field": "key"} is organisation.key.

# The field one criterion names, as the single string the lists below use.
field_named(entry) := concat(".", [entry.scope, entry.field]) if {
	is_string(entry.field)
	is_string(entry.scope)
	entry.scope != "product"
} else := entry.field if {
	is_string(entry.field)
}

# The attribute one criterion names, spelt out the same way.
attribute_named(entry) := concat(".", [entry.scope, entry.attribute]) if {
	is_string(entry.attribute)
	is_string(entry.scope)
	entry.scope != "product"
} else := entry.attribute if {
	is_string(entry.attribute)
}

requested_filter_fields contains name if {
	some entry in filter_entries
	name := field_named(entry)
}

requested_filter_attributes contains name if {
	some entry in filter_entries
	name := attribute_named(entry)
}

requested_sort_fields contains name if {
	some entry in sort_entries
	name := field_named(entry)
}

requested_sort_attributes contains name if {
	some entry in sort_entries
	name := attribute_named(entry)
}

# Free text is matched against the product's name and description, so asking for it is asking to
# search `description`: a caller not allowed that field is refused, not quietly given less.
requested_filter_fields contains "description" if text_requested

default text_requested := false

text_requested if {
	is_string(body.text)
	body.text != ""
}

# ---------------------------------------------------------------------------
# 4. What the caller may ask for
# ---------------------------------------------------------------------------

# Everything about a product that anyone may search on: what it is called, whose it is, where its
# data comes from and who uses it.
searchable_fields := [
	"description",
	"name",
	"organisation.key",
	"organisation.name",
	"source",
	"subscribedBy",
	"topic",
	"type",
]

# ...minus whatever this caller may not see. A hidden field is not filterable, sortable or
# text-searchable, so it cannot be probed by asking about it.
allowed_filtered_fields := sort([name |
	some name in searchable_fields
	not name in product.hidden_fields
])

# Attributes: everyone may search by record unit and quality, and by the organisation's own
# descriptive attributes. Those four are named here because a rule cannot read the attribute
# catalogue - the entitlement attributes (authorised_classifications, permitted_purposes) are
# flagged sensitive there and are withheld by mask_sensitive_attributes instead.
allowed_attribute_set contains "quality_designation"

allowed_attribute_set contains "record_unit"

allowed_attribute_set contains "organisation.jurisdictions"

allowed_attribute_set contains "organisation.responsibility_areas"

# The rest is earned, one attribute at a time.

# Researchers search by how finely grained a product's time series is.
allowed_attribute_set contains "temporal_resolution" if has_purpose("statistical_analysis")

# From OFFICIAL-SENSITIVE up, a caller may search by how identifiable the records are.
allowed_attribute_set contains "identifiability" if cleared_to_official_sensitive

# From SECRET up, a caller may search by which vulnerable populations a product covers.
allowed_attribute_set contains "population_risk_tags" if cleared_to_secret

allowed_filtered_attributes := sort(allowed_attribute_set)

# Everything the caller named and may not have, however it meant to use it: a sort key it may not
# use is reported alongside a filter it may not use, since both are refused. A request with no
# body names nothing, so both lists come out empty of their own accord.
denied_field_set contains name if {
	some name in requested_filter_fields
	not name in allowed_filtered_fields
}

denied_field_set contains name if {
	some name in requested_sort_fields
	not name in allowed_filtered_fields
}

denied_filtered_fields := sort(denied_field_set)

denied_attribute_set contains name if {
	some name in requested_filter_attributes
	not name in allowed_filtered_attributes
}

denied_attribute_set contains name if {
	some name in requested_sort_attributes
	not name in allowed_filtered_attributes
}

denied_filtered_attributes := sort(denied_attribute_set)

# Free text searches the name and the description, minus whatever the caller may not use or see.
text_search_fields := sort([name |
	some name in ["description", "name"]
	name in allowed_filtered_fields
	not name in masked_filtered_fields
])

# ---------------------------------------------------------------------------
# 5. What is shown of each product
# ---------------------------------------------------------------------------

# What may be selected at all: the vocabulary minus what this clearance hides. A field absent from
# here is never read from the database, so nothing has to be stripped from a result afterwards.
visible_fields := product.visible_fields

masked_filtered_fields := product.hidden_fields

# Attributes masked by name; none today. Sensitivity itself is not a list here: which attributes
# are sensitive is recorded in the attribute catalogue, and the flag below says whether this caller
# has them withheld - in every scope, and without a rule change when a new one is flagged.
masked_filtered_attributes := []

# Below SECRET, a caller does not see the values of attributes the catalogue flags as sensitive.
default mask_sensitive_attributes := false

mask_sensitive_attributes if not cleared_to_secret

# Who uses a product is withheld by clearance - but never from the organisation offering it. The
# condition is a predicate the database evaluates per product, in the same query that finds the
# products, so per-product visibility costs no second decision.
default unmask_when := []

unmask_when := [{
	"names": ["subscribedBy", "consumers"],
	"when": own_products_node,
}] if organisation_known

# ---------------------------------------------------------------------------
# 6. Which products the caller may see
#
# The row filter is the condition every returned product must satisfy, as a predicate tree in the
# filter_contract grammar. It becomes the query's WHERE clause; a view AND-s the product's own id
# onto the same tree.
# ---------------------------------------------------------------------------

# A refused request returns nothing at all, which is narrower than what the caller may see: no
# rows, never all rows. {"type": "literal", "value": false} is the grammar's "match nothing".
row_filter(allowed) := permitted_products if {
	allowed
} else := {"type": "literal", "value": false}

# An organisation sees its own products whatever the conditions below say; everything else has to
# satisfy all of them. Match nothing again if we could not even name the organisation, which
# cannot happen to an allowed request - gate_reasons refuses that one first.
default permitted_products := {"type": "literal", "value": false}

permitted_products := {"type": "group", "combinator": "or", "nodes": [
	own_products_node,
	{"type": "group", "combinator": "and", "nodes": row_filter_nodes},
]} if organisation_known

# The caller's own products, named by the organisation that offers them. A policy-origin comparison
# may name a field the caller may neither filter on nor see: that is how policy restricts by
# something it also hides.
own_products_node := {
	"type": "comparison",
	"field": "organisation.key",
	"operator": "eq",
	"values": [input.subject.organisation.key],
}

# The four conditions a product that is not the caller's own must satisfy. Two always apply; two
# apply only to some callers, so the list is written out once per case - read down until the line
# whose conditions the caller matches.
row_filter_nodes := [identifiability_node, quality_node, risk_tags_node, remit_node] if {
	risk_tags_restricted
	local_remit
} else := [identifiability_node, quality_node, risk_tags_node] if {
	risk_tags_restricted
} else := [identifiability_node, quality_node, remit_node] if {
	local_remit
} else := [identifiability_node, quality_node]

# Only records this clearance may see - see permitted_identifiability below.
identifiability_node := {
	"type": "comparison",
	"attribute": "identifiability",
	"operator": "in",
	"values": permitted_identifiability,
}

# Only data of a quality this caller's purposes admit - see permitted_quality below.
quality_node := {
	"type": "comparison",
	"attribute": "quality_designation",
	"operator": "in",
	"values": permitted_quality,
}

# Below SECRET, nothing tagged as being about a vulnerable population.
risk_tags_node := {
	"type": "comparison",
	"attribute": "population_risk_tags",
	"operator": "none_of",
	"values": risk_tags,
}

# The remit boundary as data rather than a duty: a caller with a local remit sees only products
# covering somewhere in its own remit, decided by the database like every other condition.
remit_node := {
	"type": "comparison",
	"attribute": "coverage_jurisdictions",
	"operator": "any_of",
	"values": jurisdictions,
}

# --- The conditions themselves, also read directly by the discovery oracle ---

# How identifiable a product's records may be, by the clearance the caller holds. Read downwards:
# the first line whose clearance the caller has is the one that answers.
#
#   SECRET              everything, including records that name a person or business
#   OFFICIAL-SENSITIVE  also records that are pseudonymised
#   OFFICIAL            only records that identify nobody
#   no clearance        nothing
permitted_identifiability := ["anonymised", "directly_identifiable", "non_personal", "pseudonymised"] if {
	cleared_to_secret
} else := ["anonymised", "non_personal", "pseudonymised"] if {
	cleared_to_official_sensitive
} else := ["anonymised", "non_personal"] if {
	cleared_to_official
} else := []

# How finished a product's data may be, by the purposes the caller holds. Read downwards: the
# first line whose purpose the caller has is the one that answers.
#
#   statistical_analysis  research also sees experimental data
#   regulatory_oversight  regulators also see provisional data
#   any other purpose     only data that has been validated
permitted_quality := ["experimental", "provisional", "validated"] if {
	has_purpose("statistical_analysis")
} else := ["provisional", "validated"] if {
	has_purpose("regulatory_oversight")
} else := ["validated"]

# The populations whose products are kept from anyone below SECRET.
risk_tags := ["children", "domestic_abuse_survivors", "protected_witnesses", "rare_condition_cohorts"]

default risk_tags_restricted := false

risk_tags_restricted if not cleared_to_secret

# ---------------------------------------------------------------------------
# 7. Limits and duties
# ---------------------------------------------------------------------------

# Page size grows with clearance. A view returns one product and ignores it; it is part of the
# contract all the same, because the two documents are one document.
#
#   SECRET              100 products a page
#   OFFICIAL-SENSITIVE   50
#   anything lower       20
max_page_size := 100 if {
	cleared_to_secret
} else := 50 if {
	cleared_to_official_sensitive
} else := 20

# What the service must do as well as search. An obligation it does not recognise refuses the
# search, so nothing may be added here that DiscoveryObligations does not know.

# Every search is recorded, for every caller.
obligation_set contains "audit_access"

# Something is being withheld from the results, so the service must leave it out.
obligation_set contains "mask_response" if count(masked_filtered_fields) > 0

obligation_set contains "mask_response" if count(masked_filtered_attributes) > 0

obligation_set contains "mask_response" if mask_sensitive_attributes

# Research data is released in aggregate, a duty on whoever consumes the product later.
obligation_set contains "aggregate_before_release" if has_purpose("statistical_analysis")

obligations := sort(obligation_set)
