package management_node

default allow = true

# Product discovery (ProductDiscoveryService) evaluates one decision per candidate product,
# with resource "product:{id}" and action "discover" - see PolicyInput. A real discovery
# policy belongs here once authored (see docs/POLICY_ENFORCEMENT_TESTING.md).
