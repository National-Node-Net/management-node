/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.handlers.GlobalExceptionHandler;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductDiscoveryService;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyDecisionOutputArgumentResolver;

/**
 * Covers {@code POST /api/v1/product/discover} while it is deliberately disconnected from
 * {@link ProductDiscoveryService}: the endpoint binds and validates the {@code text}/{@code
 * filters} criteria, but always answers with an empty product list. The candidate-lookup and per-product filtering scenarios live in
 * {@code ProductDiscoveryServiceImplTest} until the endpoint is wired back up.
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProductController controller = new ProductController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver(),
                        new PolicyDecisionOutputArgumentResolver(opaProperties()))
                .build();
        authenticateAs("client-1");
    }

    private static OpaProperties opaProperties() {
        return new OpaProperties(
                true,
                "http://localhost:8181",
                "/v1/data/management_node/decision",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                List.of("/api/v1/product/**"),
                List.of("content-type"),
                false,
                false);
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
    void emptyBody_treatedAsNoCriteria() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").isEmpty());
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
    }

    @Test
    void oversizedText_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());
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
    }

    @Test
    void oversizedFilterName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"" + "k".repeat(151) + "\":\"v\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }

    /** A null filter value is odd but legal JSON, and must not become a 500. */
    @Test
    void nullFilterValue_isAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"key 1\":null}}"))
                .andExpect(status().isOk());
    }
}
