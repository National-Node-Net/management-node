# METADATA
# title: Product vocabulary
# description: |
#   What the product rules need to know about the product model, in one place:
#
#     - fields     - properties of the product itself, as the API names them
#                    (columns of the product table, plus its relationships);
#     - attributes - policy attributes stored against a product in policy_attribute_value.
#
#   A rule answers separately for fields and for attributes, so a search layer knows whether
#   to filter a column or join the attribute table.
package lib.product

import data.lib.entitlements.clearance

# Every field a caller can name, as it appears in the API (ProductDTO).
fields := {"id", "producerId", "name", "topic", "type", "source", "consumers", "configurations", "policyAttributes"}

# Fields that reveal who uses a product and on what terms, withheld by clearance:
#   SECRET and above      - nothing withheld
#   OFFICIAL-SENSITIVE    - who consumes it, and on what terms
#   anything lower        - also the product's attributes and where its data comes from
default hidden_fields := ["configurations", "consumers", "policyAttributes", "source"]

hidden_fields := [] if clearance >= 3

hidden_fields := ["configurations", "consumers"] if clearance == 2

# Attributes marked sensitive in the attribute catalogue; their values are masked for anyone
# not cleared to SECRET.
sensitive_attributes := ["population_risk_tags"]
