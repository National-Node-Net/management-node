# METADATA
# title: Shared decision vocabulary
# description: |
#   The contract identifier and the deny-shaped document, defined once so the dispatcher and
#   every rule agree on them. A rule that spelt the contract its own way would be refused as
#   a contract mismatch; one that invented its own deny shape could omit a field the service
#   reads. The organisation helpers live here for the same reason: "is the caller's
#   organisation known" must mean one thing in every rule.
package lib.decision

# The version of the decision document every rule under data.policies promises to return.
# Bumped only when the document's shape changes incompatibly.
contract := "management-node.decision/1"

# Every field closed: no access, no reasons, no details. Rules start from
# this and state what they grant, so a field a rule forgets to set stays denied.
deny_shape := {
	"allow": false,
	"reasons": [],
	"details": {},
}

# The caller's organisation, when it is known. The service sends a null key when the token
# carries no organisation claim, so presence means a non-empty string, not merely a field.
default organisation_known := false

organisation_known if {
	key := input.subject.organisation.key
	is_string(key)
	key != ""
}

default organisation_attributes := {}

organisation_attributes := attributes if {
	attributes := input.subject.organisation.attributes
	is_object(attributes)
}
