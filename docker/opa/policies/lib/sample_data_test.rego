# METADATA
# title: Sample organisations and products
# description: |
#   The ORGANISATION and PRODUCT attributes exactly as the dev database stores them (schema
#   management-node), shaped the way the service sends them to the PDP. The rule tests use these
#   rather than invented values, so each test pins an outcome described in
#   docker/opa/policy_sample_stories.md.
package sample_data

organisations := {
	"ENV": {
		"authorised_classifications": ["OFFICIAL", "SECRET"],
		"department_id": "dept-environment",
		"jurisdictions": ["England", "Wales"],
		"permitted_purposes": ["regulatory_oversight", "service_delivery"],
		"responsibility_areas": ["environmental_protection", "flood_risk_management"],
	},
	"HEG": {
		"authorised_classifications": ["OFFICIAL", "OFFICIAL-SENSITIVE"],
		"department_id": "dept-housing",
		"jurisdictions": ["England", "Scotland"],
		"permitted_purposes": ["service_delivery", "statistical_analysis"],
		"responsibility_areas": ["housing_delivery", "land_availability"],
	},
	"BCC": {
		"authorised_classifications": ["OFFICIAL"],
		"department_id": "dept-local-government",
		"jurisdictions": ["Bristol", "England"],
		"nationality": "GB",
		"permitted_purposes": ["service_delivery"],
		"responsibility_areas": ["public_health", "urban_planning"],
	},
}

products := {
	"1": {
		"identifiability": "pseudonymised",
		"quality_designation": "validated",
		"record_unit": "property",
		"temporal_resolution": "annual",
	},
	"2": {
		"identifiability": "directly_identifiable",
		"population_risk_tags": ["domestic_abuse_survivors", "protected_witnesses"],
		"quality_designation": "provisional",
		"record_unit": "household",
		"temporal_resolution": "event_level",
	},
	"3": {
		"identifiability": "non_personal",
		"quality_designation": "validated",
		"record_unit": "geographic_area",
		"temporal_resolution": "daily",
	},
}

# The input for a caller from organisation `org` (a key of `organisations`).
input_for(org, action, method, body) := {
	"subject": {"kind": "user", "organisation": {"key": org, "attributes": organisations[org]}},
	"action": action,
	"resource": {"kind": "product"},
	"request": {"method": method, "body": body},
}

# The same input with no organisation claim.
anonymous_input(action, method, body) := {
	"subject": {"kind": "user", "organisation": {"key": null, "attributes": {}}},
	"action": action,
	"resource": {"kind": "product"},
	"request": {"method": method, "body": body},
}

# The same input for an organisation key that has no attributes, e.g. ia-data-product-catalogue-ui
# or a key matching no row such as FEDERATOR_ENV.
unmatched_input(action, method, body) := {
	"subject": {"kind": "user", "organisation": {"key": "FEDERATOR_ENV", "attributes": {}}},
	"action": action,
	"resource": {"kind": "product"},
	"request": {"method": method, "body": body},
}
