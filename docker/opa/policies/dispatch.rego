# METADATA
# title: Policy dispatcher
# description: |
#   The single entrypoint the Management Node queries: POST /v1/data/dispatch/decision.
#
#   The service names what is being asked - input.resource.kind and input.action, taken from
#   the handler's @Policy annotation - and never which rule answers. That mapping lives here and
#   in data.routing, so a rule can be added, shared between actions or rolled back without a
#   Java release.
#
#   Resolution, first that applies wins:
#     1. route             - an enabled data.routing.routes entry for (resource, action)
#     2. exact             - data.policies[resource][action]
#     3. resource_fallback - data.policies[resource].fallback
#     4. global_fallback   - data.policies.fallback
#
#   Steps fall through only when a module is ABSENT. A route naming a missing module, or a
#   selected module declaring a different contract, denies instead: silently answering with a
#   more general rule would hide a broken rule behind one that was never meant to decide.
#
#   Rules are reached by dynamic reference into data.policies, so a module becomes routable
#   simply by being loaded under that prefix.
package dispatch

import data.lib.decision.contract
import data.lib.decision.deny_shape

# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

# Read the rule below as a list of things that can be wrong, in order. The first one that is
# true refuses the request; if none is, the rule that was selected answers it.
#
#   nothing answers this resource and action  ->  refuse, dispatch.no_policy
#   a route names a rule that is not loaded   ->  refuse, dispatch.route_policy_missing
#   the rule speaks a different contract      ->  refuse, dispatch.contract_mismatch
#   the rule did not decide for this request  ->  refuse, dispatch.decision_undefined
#   otherwise                                 ->  the rule's own answer
#
# Every one of these is a refusal rather than a fall-through to something more general, because
# a broken rule quietly replaced by a fallback is a broken rule nobody notices.

# METADATA
# title: Dispatched decision
# description: The selected rule's decision plus provenance, or a deny-shaped document.
# entrypoint: true
decision := refusal("dispatch.no_policy") if {
	selection.resolution == "none"
} else := refusal("dispatch.route_policy_missing") if {
	not module_present(selection.path)
} else := refusal("dispatch.contract_mismatch") if {
	# object.get, not a plain reference: a module with no contract at all must mismatch,
	# whereas `selected_module.contract != contract` would be undefined and let it through.
	object.get(selected_module, "contract", null) != contract
} else := refusal("dispatch.decision_undefined") if {
	# object.get, not selected_module.decision: the compiler lifts a reference out of a function
	# argument before applying `not`, so a missing key would fail this body instead of denying.
	not is_object(object.get(selected_module, "decision", null))
} else := delegated

# A refusal says no, gives the one reason, and still records which rule was involved - so a
# reader of the logs can tell these four apart afterwards.
refusal(reason) := object.union(deny_shape, {
	"reasons": [reason],
	"policy": provenance,
})

# ---------------------------------------------------------------------------
# Selection
# ---------------------------------------------------------------------------

requested_resource := object.get(input, ["resource", "kind"], "")

requested_action := object.get(input, ["action"], "")

# `else` gives the ordering: a later step is only considered when every earlier one is
# undefined. A matched route is final even when its module is missing - that is reported
# as route_policy_missing above, never quietly replaced by exact or a fallback.
selection := {"resolution": "route", "path": split(route.policy, ".")} if {
	route
} else := {"resolution": "exact", "path": [requested_resource, requested_action]} if {
	addressable(requested_resource)
	addressable(requested_action)
	module_present([requested_resource, requested_action])
} else := {"resolution": "resource_fallback", "path": [requested_resource, "fallback"]} if {
	addressable(requested_resource)
	module_present([requested_resource, "fallback"])
} else := {"resolution": "global_fallback", "path": ["fallback"]} if {
	module_present(["fallback"])
} else := {"resolution": "none", "path": []}

# `fallback` is reserved at both levels. Without this guard a request for resource
# `fallback` would address the global fallback's own rules (data.policies.fallback.decision)
# as though they were modules.
addressable(name) if {
	is_string(name)
	name != ""
	name != "fallback"
}

selected_module := module(selection.path)

# A module is an object of rule values that declares itself as one: a `contract`, a `version` or
# a `decision`. Anything else at that path - a string rule such as a fallback's `contract`, or a
# whole resource object reached by a one-segment route - is not a module and counts as absent.
#
# Presence deliberately does not require `decision`. A rule whose decision is undefined for this
# input still declares its contract and version, so it is found and refused with
# dispatch.decision_undefined; keying presence on `decision` would make such a rule look absent
# and hand the request to a fallback, which may allow what the dedicated rule never decided.
# The contract is not part of this test either, so a module declaring the wrong contract is
# found and refused rather than skipped in favour of a fallback.
module(path) := m if {
	count(path) == 2
	m := data.policies[path[0]][path[1]]
	is_module(m)
} else := m if {
	path == ["fallback"]
	m := data.policies.fallback
	is_module(m)
}

is_module(m) if {
	is_object(m)
	some key in ["contract", "version", "decision"]
	key in object.keys(m)
}

module_present(path) if is_object(module(path))

# ---------------------------------------------------------------------------
# Routing
#
# Lowest priority wins; the id breaks ties so the winner never depends on file order.
# ---------------------------------------------------------------------------

route := ordered[0][2] if {
	candidates := [[entry.priority, entry.id, entry] |
		some entry in data.routing.routes
		entry.enabled == true
		entry.resource == requested_resource
		entry.action == requested_action
	]
	count(candidates) > 0
	ordered := sort(candidates)
}

# ---------------------------------------------------------------------------
# Delegation
# ---------------------------------------------------------------------------

# Only the contract's fields are copied from the rule, each type-checked against its deny
# default: a rule cannot forge `policy`, and a malformed field narrows access rather than
# leaking through. `allow` must be literally true.
delegated := {
	"allow": object.get(answer, "allow", false) == true,
	"reasons": sort({reason |
		some reason in array.concat(list_field("reasons"), resolution_reasons)
	}),
	"policy": provenance,
	"details": details_field,
}

answer := selected_module.decision

list_field(name) := value if {
	value := answer[name]
	is_array(value)
} else := []

default details_field := {}

details_field := answer.details if is_object(answer.details)

# A fallback is legitimate but worth seeing: it is how an action without a dedicated rule
# gets answered, and the audit trail should say so.
resolution_reasons := ["dispatch.resource_fallback"] if {
	selection.resolution == "resource_fallback"
} else := ["dispatch.global_fallback"] if {
	selection.resolution == "global_fallback"
} else := []

# ---------------------------------------------------------------------------
# Provenance
#
# Present on every decision, deny-shaped ones included, so a caller can tell "no rule",
# "broken route" and "the rule said no" apart after the fact.
# ---------------------------------------------------------------------------

provenance := {
	"id": policy_id,
	"version": policy_version,
	"resolution": selection.resolution,
}

default policy_id := "none"

policy_id := concat(".", selection.path) if count(selection.path) > 0

default policy_version := "none"

policy_version := selected_module.version if is_string(selected_module.version)
