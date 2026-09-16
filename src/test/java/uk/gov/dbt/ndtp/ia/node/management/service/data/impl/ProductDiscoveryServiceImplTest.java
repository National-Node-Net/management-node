/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.DefaultPolicyDecisionOutput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionClient;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInputFixture;

/**
 * Verifies {@link ProductDiscoveryServiceImpl} queries candidates then evaluates one PDP
 * decision per candidate, keeping only ALLOWed products (fully permitted, partially
 * permitted, and PDP-failure scenarios, AC3/AC4/AC9), and that no denied/failed candidate's
 * data leaks into the result.
 */
@ExtendWith(MockitoExtension.class)
class ProductDiscoveryServiceImplTest {

    private static final PolicyInput INPUT = PolicyInputFixture.of("client-1", "product", "discover");

    /** Discover takes no whole-request decision, so the request decision is the permissive one. */
    private static final DefaultPolicyDecisionOutput REQUEST_DECISION = DefaultPolicyDecisionOutput.ALLOW;

    @Mock
    private ProductService productService;

    @Mock
    private PolicyDecisionClient policyDecisionClient;

    @Mock
    private PolicyAttributeService policyAttributeService;

    @InjectMocks
    private ProductDiscoveryServiceImpl productDiscoveryService;

    private ProductDTO allowedProduct;
    private ProductDTO deniedProduct;

    @BeforeEach
    void setUp() {
        allowedProduct = ProductDTO.builder().id(1L).name("Allowed").build();
        deniedProduct = ProductDTO.builder().id(2L).name("Denied").build();
    }

    @Test
    void filterAuthorised_fullyPermitted_returnsAllCandidates() {
        when(policyDecisionClient.evaluate(any())).thenReturn(DefaultPolicyDecisionOutput.ALLOW);

        List<ProductDTO> result = productDiscoveryService.filterAuthorised(
                INPUT, REQUEST_DECISION, List.of(allowedProduct, deniedProduct));

        assertThat(result).containsExactlyInAnyOrder(allowedProduct, deniedProduct);
    }

    @Test
    void filterAuthorised_partiallyPermitted_returnsOnlyAllowedAndLeaksNoDeniedData() {
        // Also covers the PDP-failure case: PolicyDecisionClient already fails closed
        // (returns DENY) on any PDP error, so a denied candidate here is indistinguishable
        // from a failed one - both are excluded the same way.
        when(policyDecisionClient.evaluate(argThatResource("1"))).thenReturn(DefaultPolicyDecisionOutput.ALLOW);
        when(policyDecisionClient.evaluate(argThatResource("2"))).thenReturn(DefaultPolicyDecisionOutput.DENY);

        List<ProductDTO> result = productDiscoveryService.filterAuthorised(
                INPUT, REQUEST_DECISION, List.of(allowedProduct, deniedProduct));

        assertThat(result).containsExactly(allowedProduct);
        assertThat(result).extracting(ProductDTO::getId).doesNotContain(2L);
        assertThat(result).extracting(ProductDTO::getName).doesNotContain("Denied");
    }

    @Test
    void filterAuthorised_noneAuthorised_returnsEmptyList() {
        when(policyDecisionClient.evaluate(any())).thenReturn(DefaultPolicyDecisionOutput.DENY);

        List<ProductDTO> result = productDiscoveryService.filterAuthorised(
                INPUT, REQUEST_DECISION, List.of(allowedProduct, deniedProduct));

        assertThat(result).isEmpty();
    }

    @Test
    void filterAuthorised_variesOnlyTheResourcePerCandidate() {
        when(policyDecisionClient.evaluate(any())).thenReturn(DefaultPolicyDecisionOutput.ALLOW);
        when(policyAttributeService.findAttributeMap(1L, PolicyAttributeScopeCode.PRODUCT))
                .thenReturn(Map.of("classification", "OFFICIAL"));

        productDiscoveryService.filterAuthorised(INPUT, REQUEST_DECISION, List.of(allowedProduct));

        verify(policyDecisionClient).evaluate(INPUT.withResource("1", Map.of("classification", "OFFICIAL")));
    }

    @Test
    void filterAuthorised_attachesEachCandidatesOwnProductAttributes() {
        when(policyDecisionClient.evaluate(any())).thenReturn(DefaultPolicyDecisionOutput.ALLOW);
        when(policyAttributeService.findAttributeMap(1L, PolicyAttributeScopeCode.PRODUCT))
                .thenReturn(Map.of("tier", 1));
        when(policyAttributeService.findAttributeMap(2L, PolicyAttributeScopeCode.PRODUCT))
                .thenReturn(Map.of("tier", 2));

        productDiscoveryService.filterAuthorised(INPUT, REQUEST_DECISION, List.of(allowedProduct, deniedProduct));

        verify(policyDecisionClient).evaluate(INPUT.withResource("1", Map.of("tier", 1)));
        verify(policyDecisionClient).evaluate(INPUT.withResource("2", Map.of("tier", 2)));
    }

    @Test
    void discover_queriesCandidatesThenFiltersByPolicy() {
        when(productService.findDiscoveryCandidates("Alpha", "topic-1", "TypeA"))
                .thenReturn(List.of(allowedProduct, deniedProduct));
        when(policyDecisionClient.evaluate(argThatResource("1"))).thenReturn(DefaultPolicyDecisionOutput.ALLOW);
        when(policyDecisionClient.evaluate(argThatResource("2"))).thenReturn(DefaultPolicyDecisionOutput.DENY);

        ProductDiscoveryResponseDTO result =
                productDiscoveryService.discover(INPUT, REQUEST_DECISION, "Alpha", "topic-1", "TypeA");

        assertThat(result.products()).containsExactly(allowedProduct);
    }

    @Test
    void discover_noCandidates_returnsEmptyResponse() {
        when(productService.findDiscoveryCandidates(any(), any(), any())).thenReturn(List.of());

        ProductDiscoveryResponseDTO result =
                productDiscoveryService.discover(INPUT, REQUEST_DECISION, null, null, null);

        assertThat(result.products()).isEmpty();
    }

    private PolicyInput argThatResource(String productId) {
        return org.mockito.ArgumentMatchers.argThat(input -> input != null
                && input.resource() != null
                && productId.equals(input.resource().id()));
    }
}
