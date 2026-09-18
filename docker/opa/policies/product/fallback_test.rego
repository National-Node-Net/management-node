# METADATA
# title: Product resource fallback tests
# description: Read-only access for a known organisation, and each reason it is refused.
package product_fallback_test

import data.policies.product.fallback

org := {"key": "FEDERATOR_ENV", "attributes": {}}

request(organisation, method) := {
	"subject": {"organisation": organisation},
	"resource": {"kind": "product", "id": "42"},
	"action": "view",
	"request": {"method": method},
}

test_get_by_known_organisation_is_allowed_with_read_access if {
	decision := fallback.decision with input as request(org, "GET")

	decision.allow == true
	decision.reasons == []
	decision.details == {"access_level": "read"}
}

test_head_is_read_only if {
	decision := fallback.decision with input as request(org, "HEAD")

	decision.allow == true
}

test_post_is_denied_as_not_read_only if {
	decision := fallback.decision with input as request(org, "POST")

	decision.allow == false
	decision.reasons == ["action.not_read_only"]
	decision.details == {}
}

test_missing_organisation_is_denied if {
	decision := fallback.decision with input as request({"attributes": {}}, "GET")

	decision.allow == false
	decision.reasons == ["organisation.missing"]
	decision.details == {}
}

# The service sends a null key, not an absent one, when the token has no organisation claim.
test_null_or_empty_organisation_key_is_missing if {
	null_key := fallback.decision with input as request({"key": null}, "GET")
	empty_key := fallback.decision with input as request({"key": ""}, "GET")

	null_key.reasons == ["organisation.missing"]
	empty_key.reasons == ["organisation.missing"]
}

test_every_failing_condition_is_reported_sorted if {
	decision := fallback.decision with input as request({}, "DELETE")

	decision.reasons == ["action.not_read_only", "organisation.missing"]
}
