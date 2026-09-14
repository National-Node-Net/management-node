/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.dbt.ndtp.ia.node.management.exception.handlers.GlobalExceptionHandler;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProductDiscoveryResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductDiscoveryService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyRequester;

/**
 * Integration test for {@code POST /api/v1/product/discovery} wiring
 * {@link ProductDiscoveryController} to a mocked {@link ProductDiscoveryService}, covering
 * the discovery spec scenarios (fully permitted, partially filtered, no candidates, no
 * authorised products, and request validation, AC1-AC9).
 */
@ExtendWith(MockitoExtension.class)
class ProductDiscoveryControllerTest {

    @Mock
    private ProductDiscoveryService productDiscoveryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProductDiscoveryController controller = new ProductDiscoveryController(productDiscoveryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        authenticateAs("client-1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String clientId) {
        // lenient: not every test (e.g. request-validation-failure tests) reaches argument
        // resolution far enough to consult these mocks
        EnhancedPrincipal principal = new EnhancedPrincipal("subject", clientId, "test-organisation");
        Authentication authentication = mock(Authentication.class);
        lenient().when(authentication.getPrincipal()).thenReturn(principal);
        SecurityContext context = mock(SecurityContext.class);
        lenient().when(context.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(context);
    }

    private static ProductDiscoveryResponseDTO responseWith(ProductDTO... products) {
        return ProductDiscoveryResponseDTO.builder().products(List.of(products)).build();
    }

    @Test
    void fullyPermitted_returnsAllCandidates() throws Exception {
        ProductDTO product = ProductDTO.builder().id(1L).name("Alpha").build();
        when(productDiscoveryService.discover(any(PolicyRequester.class), any(), any(), any()))
                .thenReturn(responseWith(product));

        mockMvc.perform(post("/api/v1/product/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].name").value("Alpha"));
    }

    @Test
    void partiallyFiltered_returnsOnlyAuthorisedSubset() throws Exception {
        ProductDTO allowed = ProductDTO.builder().id(1L).name("Allowed").build();
        when(productDiscoveryService.discover(any(PolicyRequester.class), any(), any(), any()))
                .thenReturn(responseWith(allowed));

        mockMvc.perform(post("/api/v1/product/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].name").value("Allowed"));
    }

    @Test
    void noCandidates_returnsEmptyListNotError() throws Exception {
        when(productDiscoveryService.discover(any(PolicyRequester.class), any(), any(), any()))
                .thenReturn(responseWith());

        mockMvc.perform(post("/api/v1/product/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").isEmpty());
    }

    @Test
    void noAuthorisedProducts_returnsEmptyListNotErrorAndPassesCriteriaThrough() throws Exception {
        when(productDiscoveryService.discover(any(PolicyRequester.class), any(), any(), any()))
                .thenReturn(responseWith());

        mockMvc.perform(post("/api/v1/product/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alpha\",\"topic\":\"topic-1\",\"type\":\"TypeA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").isEmpty());

        verify(productDiscoveryService)
                .discover(new PolicyRequester("client-1", "test-organisation", null), "Alpha", "topic-1", "TypeA");
    }

    @Test
    void filterMatchingDeniedProduct_stillExcludedFromResponse() throws Exception {
        // A search filter matching a product does not widen what the PDP authorises: the
        // candidate query narrows by filter, but the PDP filter (mocked here as denying it)
        // still wins.
        when(productDiscoveryService.discover(any(PolicyRequester.class), eq("Restricted"), any(), any()))
                .thenReturn(responseWith());

        mockMvc.perform(post("/api/v1/product/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Restricted\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").isEmpty());
    }

    @Test
    void invalidRequestBody_oversizedField_returns400() throws Exception {
        String oversizedName = "x".repeat(51);

        mockMvc.perform(post("/api/v1/product/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + oversizedName + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productDiscoveryService);
    }

    @Test
    void malformedJsonBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/product/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productDiscoveryService);
    }

    @Test
    void emptyBody_treatedAsNoFilter() throws Exception {
        when(productDiscoveryService.discover(any(PolicyRequester.class), isNull(), isNull(), isNull()))
                .thenReturn(responseWith());

        mockMvc.perform(post("/api/v1/product/discovery").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").isEmpty());
    }
}
