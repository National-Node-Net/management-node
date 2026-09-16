# METADATA
# title: Product discovery tests
# description: Request-level and per-candidate discovery decisions, and each refusal reason.
package product_discover_test

import data.policies.product.discover

request_level(attributes) := {
	"subject": {"organisation": {"key": "FEDERATOR_ENV", "attributes": attributes}},
	"resource": {"kind": "product"},
	"action": "discover",
	"request": {"method": "POST", "body": {"text": "search term"}},
}

candidate(attributes, product_attributes) := object.union(request_level(attributes), {
	"resource": {"kind": "product", "id": "42", "attributes": product_attributes},
})

# ---------------------------------------------------------------------------
# Request level: only the caller is judged
# ---------------------------------------------------------------------------

test_permitted_nationality_may_discover if {
	decision := discover.decision with input as request_level({"nationality": "GB"})

	decision.allow == true
	decision.reasons == []
	decision.masked_filtered_attributes == []
	decision.details.evaluation == "request"
}

test_other_nationality_is_refused_and_masked if {
	decision := discover.decision with input as request_level({"nationality": "FR"})

	decision.allow == false
	decision.reasons == ["organisation.nationality_not_permitted"]
	decision.masked_filtered_attributes == ["contact_email"]
}

test_missing_nationality_is_refused if {
	decision := discover.decision with input as request_level({})

	decision.allow == false
	decision.reasons == ["organisation.nationality_not_permitted"]
}

# No product exists at request level, so the classification check must not refuse it.
test_request_level_ignores_classification if {
	decision := discover.decision with input as request_level({"nationality": "GB"})

	not "product.classification_not_permitted" in decision.reasons
}

# ---------------------------------------------------------------------------
# Candidate level: the product's classification is judged too
# ---------------------------------------------------------------------------

test_candidate_with_permitted_classification_is_allowed if {
	decision := discover.decision with input as candidate({"nationality": "GB"}, {"classification": "OFFICIAL"})

	decision.allow == true
	decision.details.evaluation == "candidate"
}

test_secret_candidate_is_refused if {
	decision := discover.decision with input as candidate({"nationality": "GB"}, {"classification": "SECRET"})

	decision.allow == false
	decision.reasons == ["product.classification_not_permitted"]
}

test_unclassified_candidate_is_refused_not_assumed_safe if {
	decision := discover.decision with input as candidate({"nationality": "GB"}, {})

	decision.allow == false
	decision.reasons == ["product.classification_not_permitted"]
}

test_every_failing_condition_is_reported_sorted if {
	decision := discover.decision with input as candidate({"nationality": "FR"}, {"classification": "SECRET"})

	decision.reasons == ["organisation.nationality_not_permitted", "product.classification_not_permitted"]
}

# ---------------------------------------------------------------------------
# Through the dispatcher: POST discover is answered by this rule, not the read-only fallback
# ---------------------------------------------------------------------------

test_dispatch_resolves_discover_exactly if {
	result := data.dispatch.decision with input as request_level({"nationality": "GB"})

	result.allow == true
	result.policy.id == "product.discover"
	result.policy.resolution == "exact"
}
