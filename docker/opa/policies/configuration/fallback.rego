# METADATA
# title: Configuration resource fallback
# description: |
#   Answers every configuration action (producer, consumer) that has no dedicated rule. It
#   allows, preserving the local placeholder behaviour the configuration endpoints had before
#   policy was dispatched per rule. Who may call them is already gated by the token's roles
#   and the client certificate; this exists so local development is not blocked, and is the
#   first thing to replace with real rules.
package policies.configuration.fallback

import data.lib.decision.deny_shape

contract := "management-node.decision/1"

version := "policies.configuration.fallback/1.0.0"

# Allowed, with no reasons and empty details - everything else deny_shape holds.
decision := object.union(deny_shape, {"allow": true})
