/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductDiscoveryService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInputFactory;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInputFixture;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyDecisionOutputArgumentResolver;

/**
 * Covers {@code POST /api/v1/product/discover} while it is deliberately disconnected from
 * {@link ProductDiscoveryService}: the endpoint binds and validates the {@code text}/{@code
 * filters} criteria and hands them to the policy input, but always answers with an empty product
 * list. The candidate-lookup and per-product filtering scenarios live in
 * {@code ProductDiscoveryServiceImplTest} until the endpoint is wired back up.
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private PolicyInputFactory policyInputFactory;

    private static final PolicyInput INPUT = PolicyInputFixture.of("client-1", "product", "discover");

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProductController controller = new ProductController(policyInputFactory);
        lenient().when(policyInputFactory.create(any(), any())).thenReturn(INPUT);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver(), new PolicyDecisionOutputArgumentResolver())
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

    private ProductDiscoveryRequestDTO capturedCriteria() {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(policyInputFactory).create(any(), body.capture());
        assertThat(body.getValue()).isInstanceOf(ProductDiscoveryRequestDTO.class);
        return (ProductDiscoveryRequestDTO) body.getValue();
    }

    @Test
    void textAndFilters_areAccepted_andAnsweredWithAnEmptyProductList() throws Exception {
        mockMvc.perform(
                        post("/api/v1/product/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"text": "this is sample text",
                                 "filters": {"key 1": "value", "key 2": [1234, 33, 222]}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").isEmpty());
    }

    @Test
    void boundCriteria_reachThePolicyInput() throws Exception {
        mockMvc.perform(
                        post("/api/v1/product/discover?page=1&last_page=222")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"text": "this is sample text",
                                 "filters": {"key 1": "value", "key 2": [1234, 33, 222]}}"""))
                .andExpect(status().isOk());

        // The controller hands the bound body to the factory; no stream is read to authorise it.
        ProductDiscoveryRequestDTO criteria = capturedCriteria();
        assertThat(criteria.text()).isEqualTo("this is sample text");
        assertThat(criteria.filters())
                .containsExactly(Map.entry("key 1", "value"), Map.entry("key 2", List.of(1234, 33, 222)));
    }

    @Test
    void emptyBody_treatedAsNoCriteria() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").isEmpty());

        ProductDiscoveryRequestDTO criteria = capturedCriteria();
        assertThat(criteria.text()).isNull();
        assertThat(criteria.filters()).isEmpty();
    }

    @Test
    void emptyJsonObject_treatedAsNoCriteria() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").isEmpty());
    }

    /** An unknown top-level field is dropped at binding rather than rejected, as before. */
    @Test
    void unknownTopLevelField_isIgnored() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"planning\",\"fitlers\":{\"a\":1}}"))
                .andExpect(status().isOk());

        ProductDiscoveryRequestDTO criteria = capturedCriteria();
        assertThat(criteria.text()).isEqualTo("planning");
        assertThat(criteria.filters()).isEmpty();
    }

    @Test
    void oversizedText_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(policyInputFactory);
    }

    @Test
    void tooManyFilters_returns400() throws Exception {
        StringBuilder filters = new StringBuilder();
        for (int i = 0; i <= 50; i++) {
            filters.append(i > 0 ? "," : "").append("\"key").append(i).append("\":\"v\"");
        }

        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{" + filters + "}}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(policyInputFactory);
    }

    @Test
    void oversizedFilterName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"" + "k".repeat(151) + "\":\"v\"}}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(policyInputFactory);
    }

    @Test
    void malformedJsonBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(policyInputFactory);
    }

    /** A null filter value is odd but legal JSON, and must not become a 500. */
    @Test
    void nullFilterValue_isAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"key 1\":null}}"))
                .andExpect(status().isOk());

        assertThat(capturedCriteria().filters()).containsEntry("key 1", null);
    }
}
