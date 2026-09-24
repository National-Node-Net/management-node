# METADATA
# title: Product resource fallback
# description: |
#   Answers product actions with no dedicated rule - e.g. `view` - and any route that points
#   at it, such as `browse`. The safe default for an unnamed action is read-only: a known
#   organisation may read, and nothing that changes state is allowed until a rule for that
#   action is written.
package policies.product.fallback

import data.lib.decision.deny_shape
import data.lib.decision.organisation_known

contract := "management-node.decision/1"

version := "policies.product.fallback/1.0.0"

decision := object.union(deny_shape, {
	"allow": allow,
	"reasons": reasons,
	"details": details,
})

# Judged on the HTTP method rather than the action name: action names are free-form, so a
# rule guessing which ones are harmless would be guessing. The method is what the caller is
# actually doing.
read_only_methods := {"GET", "HEAD"}

default allow := false

allow if {
	organisation_known
	input.request.method in read_only_methods
}

reasons := sort(reason_set)

reason_set contains "organisation.missing" if not organisation_known

reason_set contains "action.not_read_only" if not input.request.method in read_only_methods

default details := {}

details := {"access_level": "read"} if allow
