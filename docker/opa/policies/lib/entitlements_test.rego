# METADATA
# title: Organisation entitlement tests
# description: Each derived fact for the three sample organisations, and for missing or mistyped attributes.
package lib_entitlements_test

import data.lib.entitlements
import data.sample_data

with_attributes(attributes) := {"subject": {"organisation": {"key": "X", "attributes": attributes}}}

org(key) := with_attributes(sample_data.organisations[key])

# Each sample organisation answers yes up to the classification it holds, and no above it.
test_each_organisation_is_cleared_to_what_it_holds if {
	# ENV holds SECRET.
	entitlements.cleared_to_secret with input as org("ENV")
	entitlements.cleared_to_official_sensitive with input as org("ENV")
	entitlements.cleared_to_official with input as org("ENV")
	not entitlements.cleared_to_top_secret with input as org("ENV")

	# HEG holds OFFICIAL-SENSITIVE.
	not entitlements.cleared_to_secret with input as org("HEG")
	entitlements.cleared_to_official_sensitive with input as org("HEG")
	entitlements.cleared_to_official with input as org("HEG")

	# BCC holds OFFICIAL.
	not entitlements.cleared_to_official_sensitive with input as org("BCC")
	entitlements.cleared_to_official with input as org("BCC")
}

# Clearance is inclusive: holding a high one answers yes to every lower one, even when the lower
# one is not recorded against the organisation.
test_a_higher_clearance_covers_every_lower_one if {
	top_secret_only := with_attributes({"authorised_classifications": ["TOP SECRET"]})

	entitlements.cleared_to_top_secret with input as top_secret_only
	entitlements.cleared_to_secret with input as top_secret_only
	entitlements.cleared_to_official_sensitive with input as top_secret_only
	entitlements.cleared_to_official with input as top_secret_only
}

# No classification recorded, or one nobody recognises, means cleared to nothing at all.
test_no_recognised_classification_is_cleared_to_nothing if {
	nothing := [
		with_attributes({}),
		with_attributes({"authorised_classifications": ["UNKNOWN"]}),
		{"subject": {}},
	]

	every request in nothing {
		not entitlements.cleared_to_official with input as request
		not entitlements.cleared_to_official_sensitive with input as request
		not entitlements.cleared_to_secret with input as request
		not entitlements.cleared_to_top_secret with input as request
	}
}

# One classification may be stored as a bare string rather than a list; it reads the same way.
test_single_valued_classification_is_read_like_a_list if {
	one := with_attributes({"authorised_classifications": "OFFICIAL-SENSITIVE"})

	entitlements.cleared_to_official_sensitive with input as one
	entitlements.cleared_to_official with input as one
	not entitlements.cleared_to_secret with input as one
}

test_purposes if {
	entitlements.has_purpose("regulatory_oversight") with input as org("ENV")
	entitlements.has_purpose("statistical_analysis") with input as org("HEG")
	not entitlements.has_purpose("statistical_analysis") with input as org("BCC")
	not entitlements.has_purpose("service_delivery") with input as with_attributes({})
}

test_every_sample_organisation_has_a_uk_jurisdiction if {
	entitlements.uk_jurisdiction with input as org("ENV")
	entitlements.uk_jurisdiction with input as org("HEG")
	entitlements.uk_jurisdiction with input as org("BCC")
}

test_no_uk_jurisdiction if {
	not entitlements.uk_jurisdiction with input as with_attributes({"jurisdictions": ["Brittany"]})
	not entitlements.uk_jurisdiction with input as with_attributes({})
}

test_jurisdictions_are_the_remit_as_data if {
	entitlements.jurisdictions == ["England", "Wales"] with input as org("ENV")
	entitlements.jurisdictions == ["Bristol", "England"] with input as org("BCC")
	entitlements.jurisdictions == [] with input as with_attributes({})
	entitlements.jurisdictions == ["Bristol"] with input as with_attributes({"jurisdictions": "Bristol"})
}

test_only_bcc_has_a_local_remit if {
	not entitlements.local_remit with input as org("ENV")
	not entitlements.local_remit with input as org("HEG")
	entitlements.local_remit with input as org("BCC")
}
