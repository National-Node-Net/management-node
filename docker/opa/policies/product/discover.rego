# METADATA
# title: Product discovery
# description: |
#   The discovery rule: an organisation of a permitted nationality may discover products, and
#   never one classified SECRET; callers of any other nationality have contact details masked.
#
#   Discovery is decided at two levels by this one rule, told apart by input.resource.id:
#     - request   - the whole POST /api/v1/product/discover call, gated by the PEP. There is no
#                   product yet, so only the caller is judged.
#     - candidate - one product at a time (input.resource.id set), asked by the discovery
#                   service. The product's classification is judged as well.
#   A candidate that carries no classification is refused rather than assumed unclassified, so
#   a missing attribute can never widen what a caller sees.
package policies.product.discover

import data.lib.decision.deny_shape
import data.lib.decision.organisation_attributes

contract := "management-node.decision/1"

version := "policies.product.discover/1.0.0"

permitted_nationalities := ["GB"]

excluded_classifications := ["SECRET"]

decision := object.union(deny_shape, {
	"allow": allow,
	"masked_filtered_attributes": masked_filtered_attributes,
	"reasons": reasons,
	"details": {
		"evaluation": evaluation,
		"permitted_nationalities": permitted_nationalities,
		"excluded_classifications": excluded_classifications,
	},
})

default allow := false

allow if count(reason_set) == 0

reasons := sort([reason | some reason in reason_set])

reason_set contains "organisation.nationality_not_permitted" if not nationality_permitted

reason_set contains "product.classification_not_permitted" if {
	evaluation == "candidate"
	not classification_permitted
}

default nationality_permitted := false

nationality_permitted if organisation_attributes.nationality in permitted_nationalities

default classification_permitted := false

classification_permitted if {
	classification := input.resource.attributes.classification
	is_string(classification)
	not classification in excluded_classifications
}

default masked_filtered_attributes := []

masked_filtered_attributes := ["contact_email"] if not nationality_permitted

default evaluation := "request"

evaluation := "candidate" if {
	id := input.resource.id
	id != null
	id != ""
}
