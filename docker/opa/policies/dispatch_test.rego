# METADATA
# title: Dispatcher tests
# description: |
#   Pins each resolution kind and each way dispatch refuses. Rules are mocked with `with` where
#   the point is the dispatcher's behaviour rather than a shipped rule's, so these tests keep
#   meaning the same thing when the shipped rules change.
package dispatch_test

import data.dispatch

known_org := {"key": "FEDERATOR_ENV", "attributes": {}}

request(resource, action, method) := {
	"subject": {"kind": "service", "clientId": "client", "organisation": known_org},
	"resource": {"kind": resource},
	"action": action,
	"request": {"method": method, "path": "/api/v1/x", "headers": {}, "query": {}},
}

allowing_module := {
	"contract": "management-node.decision/1",
	"version": "policies.mock/1.0.0",
	"decision": {
		"allow": true,
		"allowed_filtered_attributes": [],
		"denied_filtered_attributes": [],
		"masked_filtered_attributes": [],
		"reasons": [],
		"details": {},
	},
}

# ---------------------------------------------------------------------------
# Resolution kinds
# ---------------------------------------------------------------------------

test_route_resolves_to_the_named_policy if {
	decision := dispatch.decision with input as request("product", "browse", "GET")

	decision.policy == {
		"id": "product.fallback",
		"version": "policies.product.fallback/1.0.0",
		"resolution": "route",
	}
	decision.allow == true
	decision.reasons == []
}

test_exact_resolves_to_the_dedicated_rule if {
	decision := dispatch.decision with input as object.union(
		request("product", "subscribe", "POST"),
		{"request": {"body": {"productId": 42}}},
	)

	decision.policy.id == "product.subscribe"
	decision.policy.resolution == "exact"
	decision.policy.version == "policies.product.subscribe/1.0.0"
	decision.allow == true
}

test_resource_fallback_answers_an_action_with_no_rule if {
	decision := dispatch.decision with input as request("product", "view", "GET")

	decision.policy.id == "product.fallback"
	decision.policy.resolution == "resource_fallback"
	decision.allow == true
	decision.reasons == ["dispatch.resource_fallback"]
	decision.details == {"access_level": "read"}
}

test_global_fallback_answers_an_unknown_resource_and_denies if {
	decision := dispatch.decision with input as request("invoice", "read", "GET")

	decision.policy == {"id": "fallback", "version": "policies.fallback/1.0.0", "resolution": "global_fallback"}
	decision.allow == false
	decision.reasons == ["dispatch.global_fallback", "policy.no_specific_rule"]
	decision.details == {}
}

test_configuration_fallback_allows if {
	decision := dispatch.decision with input as request("configuration", "producer", "GET")

	decision.policy.id == "configuration.fallback"
	decision.policy.resolution == "resource_fallback"
	decision.allow == true
}

# `fallback` is reserved, so it must never address a fallback module's own rules as though
# they were modules.
test_reserved_fallback_resource_is_not_addressable if {
	decision := dispatch.decision with input as request("fallback", "decision", "GET")

	decision.policy.resolution == "global_fallback"
	decision.allow == false
}

test_missing_input_fields_reach_the_global_fallback if {
	decision := dispatch.decision with input as {}

	decision.policy.resolution == "global_fallback"
	decision.allow == false
}

# ---------------------------------------------------------------------------
# Refusals
# ---------------------------------------------------------------------------

test_no_policy_when_even_the_global_fallback_is_missing if {
	decision := dispatch.decision with input as request("invoice", "read", "GET")
		with data.policies as {}

	decision == {
		"allow": false,
		"allowed_filtered_attributes": [],
		"denied_filtered_attributes": [],
		"masked_filtered_attributes": [],
		"reasons": ["dispatch.no_policy"],
		"policy": {"id": "none", "version": "none", "resolution": "none"},
		"details": {},
	}
}

test_route_naming_a_missing_module_denies_without_falling_back if {
	decision := dispatch.decision with input as request("product", "browse", "GET")
		with data.routing.routes as [{
			"id": "route-broken",
			"resource": "product",
			"action": "browse",
			"policy": "product.nonexistent",
			"priority": 10,
			"enabled": true,
		}]

	decision.allow == false
	decision.reasons == ["dispatch.route_policy_missing"]
	decision.policy == {"id": "product.nonexistent", "version": "none", "resolution": "route"}
}

test_contract_mismatch_denies_without_falling_back if {
	decision := dispatch.decision with input as object.union(
		request("product", "subscribe", "POST"),
		{"request": {"body": {"productId": 42}}},
	)
		with data.policies.product.subscribe.contract as "management-node.decision/0"

	decision.allow == false
	decision.reasons == ["dispatch.contract_mismatch"]
	decision.policy.id == "product.subscribe"
	decision.policy.resolution == "exact"
	decision.details == {}
}

test_module_without_a_contract_is_a_mismatch if {
	decision := dispatch.decision with input as request("product", "view", "GET")
		with data.policies as {"product": {"view": object.remove(allowing_module, ["contract"])}}

	decision.allow == false
	decision.reasons == ["dispatch.contract_mismatch"]
	decision.policy.resolution == "exact"
}

# ---------------------------------------------------------------------------
# Routing order and delegation hygiene
# ---------------------------------------------------------------------------

test_lowest_priority_enabled_route_wins if {
	decision := dispatch.decision with input as request("product", "browse", "GET")
		with data.policies as {
			"product": {"first": allowing_module, "second": allowing_module, "third": allowing_module},
		}
		with data.routing.routes as [
			{"id": "b", "resource": "product", "action": "browse", "policy": "product.second", "priority": 20, "enabled": true},
			{"id": "a", "resource": "product", "action": "browse", "policy": "product.first", "priority": 10, "enabled": true},
			{"id": "c", "resource": "product", "action": "browse", "policy": "product.third", "priority": 1, "enabled": false},
		]

	decision.policy.id == "product.first"
}

test_rule_cannot_forge_provenance_or_widen_through_malformed_fields if {
	forging := object.union(allowing_module, {"decision": {
		"allow": "yes",
		"reasons": "not-a-list",
		"details": ["not", "an", "object"],
		"policy": {"id": "someone.else", "version": "x", "resolution": "exact"},
	}})

	decision := dispatch.decision with input as request("product", "view", "GET")
		with data.policies as {"product": {"view": forging}}

	decision.allow == false
	decision.reasons == []
	decision.details == {}
	decision.allowed_filtered_attributes == []
	decision.policy == {"id": "product.view", "version": "policies.mock/1.0.0", "resolution": "exact"}
}

# A dedicated rule whose decision is undefined for the input must deny, not fall through to a
# fallback that could allow what the dedicated rule never decided.
test_declared_rule_with_undefined_decision_denies_without_fallback if {
	policies := {
		"product": {
			"view": {"contract": "management-node.decision/1", "version": "policies.product.view/1.0.0"},
			"fallback": {
				"contract": "management-node.decision/1",
				"version": "policies.product.fallback/1.0.0",
				"decision": {
					"allow": true, "allowed_filtered_attributes": [], "denied_filtered_attributes": [],
					"masked_filtered_attributes": [], "reasons": [], "details": {},
				},
			},
		},
	}
	result := data.dispatch.decision with data.policies as policies
		with input as {"action": "view", "resource": {"kind": "product"}, "request": {"method": "GET"}}
	result.allow == false
	result.reasons == ["dispatch.decision_undefined"]
	result.policy.resolution == "exact"
}
