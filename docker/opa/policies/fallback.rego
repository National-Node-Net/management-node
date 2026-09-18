# METADATA
# title: Global fallback
# description: |
#   Answers any (resource, action) that has neither a route, a dedicated rule nor a resource
#   fallback. It denies: an endpoint annotated with @Policy before anyone wrote a rule for it
#   must fail closed, not inherit access nobody decided to grant.
package policies.fallback

import data.lib.decision.deny_shape

contract := "management-node.decision/1"

version := "policies.fallback/1.0.0"

decision := object.union(deny_shape, {"reasons": ["policy.no_specific_rule"]})
