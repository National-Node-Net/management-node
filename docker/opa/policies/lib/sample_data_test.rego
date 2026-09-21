# METADATA
# title: Sample organisations and products
# description: |
#   The ORGANISATION and PRODUCT attributes exactly as the dev database stores them (schema
#   management-node), shaped the way the service sends them to the PDP. The rule tests use these
#   rather than invented values, so each test pins an outcome described in
#   docker/opa/policy_sample_stories.md.
#
#   The products are the three of db/samples/V20250728152300__sample_data.sql followed by the
#   fourteen of V20260918150000__add_discovery_sample_dataset.sql, keyed by the id each gets from
#   its insert order, with the attribute values of V20260910160000 and V20260918150000. `owner` is
#   the organisation offering the product, resolved as the service resolves it
#   (product -> producer -> organisation); the service sends it as input.resource.owner.
#
#   Keep this file and those migrations in step: it is what makes the Rego tests and the database
#   one truth rather than two.
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
		"name": "BrownfieldLandAvailability",
		"owner": "HEG",
		"attributes": {
			"coverage_jurisdictions": ["England"],
			"identifiability": "pseudonymised",
			"quality_designation": "validated",
			"record_unit": "property",
			"temporal_resolution": "annual",
		},
	},
	"2": {
		"name": "PendingPlanningApplications",
		"owner": "BCC",
		"attributes": {
			"coverage_jurisdictions": ["Bristol"],
			"identifiability": "directly_identifiable",
			"population_risk_tags": ["domestic_abuse_survivors", "protected_witnesses"],
			"quality_designation": "provisional",
			"record_unit": "household",
			"temporal_resolution": "event_level",
		},
	},
	"3": {
		"name": "FloodRiskMapZones",
		"owner": "ENV",
		"attributes": {
			"coverage_jurisdictions": ["England", "Wales"],
			"identifiability": "non_personal",
			"quality_designation": "validated",
			"record_unit": "geographic_area",
			"temporal_resolution": "daily",
		},
	},
	"4": {
		"name": "RiverLevelTelemetry",
		"owner": "ENV",
		"attributes": {
			"coverage_jurisdictions": ["England", "Wales"],
			"identifiability": "non_personal",
			"quality_designation": "validated",
			"record_unit": "geographic_area",
			"temporal_resolution": "hourly",
		},
	},
	"5": {
		"name": "CoastalErosionForecast",
		"owner": "ENV",
		"attributes": {
			"coverage_jurisdictions": ["England"],
			"identifiability": "non_personal",
			"quality_designation": "experimental",
			"record_unit": "geographic_area",
			"temporal_resolution": "annual",
		},
	},
	"6": {
		"name": "PollutionIncidentReports",
		"owner": "ENV",
		"attributes": {
			"coverage_jurisdictions": ["England", "Wales"],
			"identifiability": "pseudonymised",
			"quality_designation": "provisional",
			"record_unit": "business",
			"temporal_resolution": "event_level",
		},
	},
	"7": {
		"name": "WaterAbstractionLicences",
		"owner": "ENV",
		"attributes": {
			"coverage_jurisdictions": ["England"],
			"identifiability": "directly_identifiable",
			"quality_designation": "validated",
			"record_unit": "business",
			"temporal_resolution": "monthly",
		},
	},
	# Deliberately missing identifiability and quality_designation: the attributes every rule
	# needs are the ones a product can be missing.
	"8": {
		"name": "UnclassifiedDraftDataset",
		"owner": "ENV",
		"attributes": {
			"coverage_jurisdictions": ["England"],
			"record_unit": "geographic_area",
		},
	},
	"9": {
		"name": "AffordableHousingCompletions",
		"owner": "HEG",
		"attributes": {
			"coverage_jurisdictions": ["England"],
			"identifiability": "anonymised",
			"quality_designation": "validated",
			"record_unit": "property",
			"temporal_resolution": "annual",
		},
	},
	"10": {
		"name": "HelpToBuyApplicants",
		"owner": "HEG",
		"attributes": {
			"coverage_jurisdictions": ["England"],
			"identifiability": "directly_identifiable",
			"population_risk_tags": ["children"],
			"quality_designation": "validated",
			"record_unit": "person",
			"temporal_resolution": "event_level",
		},
	},
	"11": {
		"name": "HousingNeedSurvey",
		"owner": "HEG",
		"attributes": {
			"coverage_jurisdictions": ["England", "Scotland"],
			"identifiability": "pseudonymised",
			"quality_designation": "experimental",
			"record_unit": "household",
			"temporal_resolution": "annual",
		},
	},
	"12": {
		"name": "EmptyHomesRegister",
		"owner": "HEG",
		"attributes": {
			"coverage_jurisdictions": ["England"],
			"identifiability": "pseudonymised",
			"quality_designation": "superseded",
			"record_unit": "property",
			"temporal_resolution": "monthly",
		},
	},
	"13": {
		"name": "ScotlandLandRegisterExtract",
		"owner": "HEG",
		"attributes": {
			"coverage_jurisdictions": ["Scotland"],
			"identifiability": "anonymised",
			"quality_designation": "validated",
			"record_unit": "property",
			"temporal_resolution": "annual",
		},
	},
	"14": {
		"name": "BristolAirQualitySensors",
		"owner": "BCC",
		"attributes": {
			"coverage_jurisdictions": ["Bristol"],
			"identifiability": "non_personal",
			"quality_designation": "validated",
			"record_unit": "geographic_area",
			"temporal_resolution": "hourly",
		},
	},
	"15": {
		"name": "BristolSchoolPlaces",
		"owner": "BCC",
		"attributes": {
			"coverage_jurisdictions": ["Bristol"],
			"identifiability": "anonymised",
			"population_risk_tags": ["children"],
			"quality_designation": "provisional",
			"record_unit": "person",
			"temporal_resolution": "annual",
		},
	},
	"16": {
		"name": "BristolTemporaryAccommodation",
		"owner": "BCC",
		"attributes": {
			"coverage_jurisdictions": ["Bristol"],
			"identifiability": "directly_identifiable",
			"population_risk_tags": ["domestic_abuse_survivors"],
			"quality_designation": "validated",
			"record_unit": "household",
			"temporal_resolution": "event_level",
		},
	},
	"17": {
		"name": "Bristol_Cycle%Counts",
		"owner": "BCC",
		"attributes": {
			"coverage_jurisdictions": ["Bristol"],
			"identifiability": "non_personal",
			"quality_designation": "validated",
			"record_unit": "geographic_area",
			"temporal_resolution": "daily",
		},
	},
}

# The input for a caller from organisation `org` (a key of `organisations`).
input_for(org, action, method, body) := {
	"subject": {"kind": "user", "organisation": {"key": org, "attributes": organisations[org]}},
	"action": action,
	"resource": {"kind": "product"},
	"request": {"method": method, "body": body},
}

# The same input judging one product, as the discovery oracle sends it: the product's id, its
# attributes, and the organisation offering it.
candidate_input(org, action, method, body, product_id) := object.union(
	input_for(org, action, method, body),
	{"resource": {
		"id": product_id,
		"owner": products[product_id].owner,
		"attributes": products[product_id].attributes,
	}},
)

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
