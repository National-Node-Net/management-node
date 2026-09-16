package management_node

# Placeholder policy: defaults to allow so local development is not blocked. This is NOT a
# policy - replace `default allow := true` with `default allow := false` and author real rules
# before relying on it for anything.
#
# EVERY policy answers with the same document, whatever it is deciding about, so the caller
# never has to know which rule replied:
#
#   {
#     "allow": true,
#     "allowed_filtered_attributes": ["name", "topic"],
#     "denied_filtered_attributes": ["internal_owner"],
#     "masked_filtered_attributes": ["contact_email"]
#   }
#
#   allow                        whether the action is permitted
#   allowed_filtered_attributes  attributes the subject may see in full
#   denied_filtered_attributes   attributes that must be withheld entirely
#   masked_filtered_attributes   attributes that may be returned only in masked form
#
# The service queries the `decision` rule (application.opa.decision-path, by default
# /v1/data/management_node/decision) and reads it into DefaultPolicyDecisionOutput. A policy
# that decides only allow/deny still returns the three lists, empty.
#
# The decision input is an object graph, not a flat set of strings:
#
#   input.subject.kind                    "service" | "user"
#   input.subject.user_id                 client id, or the user's email
#   input.subject.clientId                the OAuth client the token was issued to
#   input.subject.token                   the JWT's claims (exp/iat as epoch seconds)
#   input.subject.organisation.key        e.g. "FEDERATOR_ENV", from the token's claim
#   input.subject.organisation.attributes ORGANISATION-scoped policy attributes
#   input.action                          last path segment, e.g. "discover"
#   input.resource.kind                   preceding path segment, e.g. "product"
#   input.resource.id                     entity id, absent for whole-endpoint decisions
#   input.resource.attributes             that entity's policy attributes
#   input.request.{headers,query,path,method,body}
#
# Product discover evaluates one decision per candidate product, varying only
# input.resource.id and input.resource.attributes. See docs/POLICY_ENFORCEMENT.md.

default allow := true

default allowed_filtered_attributes := []

default denied_filtered_attributes := []

default masked_filtered_attributes := []

# The single rule the service queries. Assembled from the four rules above so that a real
# policy overrides whichever of them it has an opinion about and leaves the rest alone.
decision := {
	"allow": allow,
	"allowed_filtered_attributes": allowed_filtered_attributes,
	"denied_filtered_attributes": denied_filtered_attributes,
	"masked_filtered_attributes": masked_filtered_attributes,
}

# Example of the shape a real discover rule takes:
#
# default allow := false
#
# allow if {
#     input.action == "discover"
#     input.resource.kind == "product"
#     input.subject.organisation.attributes.nationality == "GB"
#     input.resource.attributes.classification != "SECRET"
# }
#
# masked_filtered_attributes := ["contact_email"] if {
#     input.subject.organisation.attributes.nationality != "GB"
# }
