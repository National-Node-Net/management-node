# METADATA
# title: Organisation entitlements
# description: |
#   Plain-language facts about the calling organisation, worked out from the ORGANISATION
#   attributes stored in the database. Every product rule asks these questions instead of
#   reading raw attributes, so "cleared to OFFICIAL-SENSITIVE" or "has a local remit" means
#   the same thing in every rule.
#
#   Classifications are named here, never scored: a rule says `cleared_to_secret`, not a number
#   to compare. Nothing has to be looked up to know what a rule permits.
#
#   Every fact is total: a missing or mistyped attribute gives the lowest entitlement, never
#   an error.
package lib.entitlements

import data.lib.decision.organisation_attributes

uk_nations := {"England", "Scotland", "Wales", "Northern Ireland"}

# ---------------------------------------------------------------------------
# Clearance - which classifications this organisation is authorised to handle
#
# The classifications it holds are recorded against it in the database, by name:
#   OFFICIAL, OFFICIAL-SENSITIVE, SECRET, TOP SECRET
#
# Clearance is inclusive. An organisation cleared to SECRET may see everything an
# OFFICIAL-SENSITIVE organisation may see, and everything an OFFICIAL one may see. So each
# question below means "cleared to at least this", and a rule asks the one naming the level it
# cares about:
#
#   cleared_to_official            - cleared to anything at all
#   cleared_to_official_sensitive  - OFFICIAL-SENSITIVE or better
#   cleared_to_secret              - SECRET or better
#   cleared_to_top_secret          - TOP SECRET
#
# An organisation holding none of them answers false to all four.
# ---------------------------------------------------------------------------

# The classifications recorded against this organisation, exactly as they are written there.
authorised_classifications := attribute_values("authorised_classifications")

default cleared_to_top_secret := false

cleared_to_top_secret if "TOP SECRET" in authorised_classifications

default cleared_to_secret := false

cleared_to_secret if "SECRET" in authorised_classifications

cleared_to_secret if cleared_to_top_secret

default cleared_to_official_sensitive := false

cleared_to_official_sensitive if "OFFICIAL-SENSITIVE" in authorised_classifications

cleared_to_official_sensitive if cleared_to_secret

default cleared_to_official := false

cleared_to_official if "OFFICIAL" in authorised_classifications

cleared_to_official if cleared_to_official_sensitive

# The organisation holds this purpose in `permitted_purposes`.
has_purpose(purpose) if purpose in attribute_values("permitted_purposes")

# The organisation's remit includes at least one UK nation.
default uk_jurisdiction := false

uk_jurisdiction if {
	some jurisdiction in attribute_values("jurisdictions")
	jurisdiction in uk_nations
}

# Everywhere the organisation's remit covers, sorted. A rule compares these with the area a
# product's data covers, so the caller's remit reaches the search as data rather than as a duty
# the search layer is left to work out for itself.
jurisdictions := sort(attribute_values("jurisdictions"))

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
