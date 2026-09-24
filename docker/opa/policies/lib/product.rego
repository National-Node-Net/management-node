# METADATA
# title: Product vocabulary
# description: |
#   What the product rules need to know about the product model, in one place:
#
#     - fields         - everything a caller or a policy can name about a product: the product's
#                        own properties, the members of a related entity (organisation.key), and
#                        the blocks that are shown or withheld whole (consumers, policyAttributes);
#     - hidden_fields  - what a caller at a given clearance does not see;
#     - visible_fields - the rest, which is what a search may select at all.
#
#   The set below is the vocabulary the service knows, and the SERVICE is the source of truth for
#   it: uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductField (field names) and
#   ProductBlock (block names). A name here the service does not know can never be selected, and a
#   name the service knows but this set omits can never be filtered on, so the two are kept in step.
#
#   Attributes are deliberately absent: they are an open set owned by the data
#   (policy_attribute_value), not a vocabulary a rule can enumerate. Which of them are sensitive is
#   data too (policy_attribute_definition.sensitive); a rule only says whether this caller has
#   sensitive values withheld, through details.mask_sensitive_attributes.
package lib.product

import data.lib.entitlements.cleared_to_official_sensitive
import data.lib.entitlements.cleared_to_secret

# Every field and block a caller or a policy can name, qualified as one string: bare for the
# product's own properties, prefixed with its entity otherwise.
fields := {
	"name",
	"description",
	"topic",
	"type",
	"source",
	"organisation",
	"organisation.key",
	"organisation.name",
	"producer",
	"producer.name",
	"producer.description",
	"producer.active",
	"consumers",
	"subscribedBy",
	"policyAttributes",
}

# Fields that reveal who uses a product and on what terms, withheld by clearance:
#   SECRET and above      - nothing withheld
#   OFFICIAL-SENSITIVE    - the consumers and the terms they hold, but who uses it stays visible
#   anything lower        - also who uses it, the product's attributes and where its data comes from
#
# The organisation and producer offering a product are in nobody's list: anyone who may discover a
# product may see whose it is.
hidden_fields := [] if {
	cleared_to_secret
} else := ["consumers"] if {
	cleared_to_official_sensitive
} else := ["consumers", "policyAttributes", "source", "subscribedBy"]

# What is left, which is what a search may put in its SELECT list. A field absent from here is
# never read from the database, so nothing has to be stripped from a result afterwards.
visible_fields := sort([name |
	some name in fields
	not name in hidden_fields
])
