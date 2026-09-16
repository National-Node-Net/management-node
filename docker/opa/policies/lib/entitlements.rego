# METADATA
# title: Organisation entitlements
# description: |
#   Plain-language facts about the calling organisation, worked out from the ORGANISATION
#   attributes stored in the database. Every product rule asks these questions instead of
#   reading raw attributes, so "cleared to OFFICIAL-SENSITIVE" or "has a local remit" means
#   the same thing in every rule.
#
#   Every fact is total: a missing or mistyped attribute gives the lowest entitlement, never
#   an error.
package lib.entitlements

import data.lib.decision.organisation_attributes

# Security classifications, lowest first. An organisation's clearance is the highest one it
# is authorised to handle.
classification_rank := {
	"OFFICIAL": 1,
	"OFFICIAL-SENSITIVE": 2,
	"SECRET": 3,
	"TOP SECRET": 4,
}

uk_nations := {"England", "Scotland", "Wales", "Northern Ireland"}

# The highest classification rank the organisation holds; 0 when it holds none.
clearance := max({rank |
	some classification in attribute_values("authorised_classifications")
	rank := classification_rank[classification]
} | {0})

# The organisation holds this purpose in `permitted_purposes`.
has_purpose(purpose) if purpose in attribute_values("permitted_purposes")

# The organisation's remit includes at least one UK nation.
default uk_jurisdiction := false

uk_jurisdiction if {
	some jurisdiction in attribute_values("jurisdictions")
	jurisdiction in uk_nations
}

# The organisation's remit includes a local area (e.g. Bristol), not only whole nations.
default local_remit := false

local_remit if {
	some jurisdiction in attribute_values("jurisdictions")
	not jurisdiction in uk_nations
}

# An attribute's string values as a set. Multi-valued attributes arrive as arrays and
# single-valued ones as scalars; both read the same way here.
attribute_values(name) := {value |
	some value in organisation_attributes[name]
	is_string(value)
} if {
	is_array(organisation_attributes[name])
} else := {organisation_attributes[name]} if {
	is_string(organisation_attributes[name])
} else := set()
