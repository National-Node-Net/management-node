# METADATA
# title: Organisation entitlement tests
# description: Each derived fact for the three sample organisations, and for missing or mistyped attributes.
package lib_entitlements_test

import data.lib.entitlements
import data.sample_data

with_attributes(attributes) := {"subject": {"organisation": {"key": "X", "attributes": attributes}}}

org(key) := with_attributes(sample_data.organisations[key])

test_clearance_is_the_highest_classification_held if {
	entitlements.clearance == 3 with input as org("ENV")
	entitlements.clearance == 2 with input as org("HEG")
	entitlements.clearance == 1 with input as org("BCC")
}

test_clearance_is_zero_without_classifications if {
	entitlements.clearance == 0 with input as with_attributes({})
	entitlements.clearance == 0 with input as with_attributes({"authorised_classifications": ["UNKNOWN"]})
	entitlements.clearance == 0 with input as {"subject": {}}
}

test_single_valued_classification_is_read_like_a_list if {
	entitlements.clearance == 2 with input as with_attributes({"authorised_classifications": "OFFICIAL-SENSITIVE"})
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

test_only_bcc_has_a_local_remit if {
	not entitlements.local_remit with input as org("ENV")
	not entitlements.local_remit with input as org("HEG")
	entitlements.local_remit with input as org("BCC")
}
