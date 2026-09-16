/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.handlers.GlobalExceptionHandler;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductSubscriptionResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductSubscriptionPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductViewPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductDiscoveryService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyProvenance;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyDecisionArgumentResolver;

/**
 * Covers the product endpoints at the controller boundary.
 *
 * <p>{@code POST /api/v1/product/discover} is deliberately disconnected from
 * {@link ProductDiscoveryService}: it binds and validates the {@code text}/{@code filters}
 * criteria, but always answers with an empty product list. The candidate-lookup and per-product
 * filtering scenarios live in {@code ProductDiscoveryServiceImplTest}.
 *
 * <p>The PEP is not part of a standalone MockMvc setup, so the subscribe and view tests publish a
 * decision on the request themselves - exactly what the PEP does before the handler runs - and
 * check how the handler uses it.
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private ProductService productService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvc(true);
        authenticateAs("client-1");
    }

    private MockMvc mockMvc(boolean opaEnabled) {
        return MockMvcBuilders.standaloneSetup(new ProductController(productService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver(),
                        new PolicyDecisionArgumentResolver(opaProperties(opaEnabled)))
                .build();
    }

    private static OpaProperties opaProperties(boolean opaEnabled) {
        return new OpaProperties(
                opaEnabled,
                "http://localhost:8181",
                "/v1/data/dispatch/decision",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
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

    // ---------------------------------------------------------------------------------------
    // Discover: the request-level decision reaches the handler and is logged
    // ---------------------------------------------------------------------------------------

    private static String discoverLogAfter(MockMvc mvc, PolicyDecision<?> published) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ProductController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var request = post("/api/v1/product/discover")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"text\":\"search term\"}");
            if (published != null) {
                request.requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, published);
            }
            mvc.perform(request).andExpect(status().isOk());
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("Product discover"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void discover_publishedDecision_isAvailableToTheHandlerAndLogged() throws Exception {
        PolicyDecision<ProductDiscoveryPolicyDecisionDetails> published = new PolicyDecision<>(
                true,
                List.of(),
                new PolicyProvenance("product.discover", "policies.product.discover/1.0.0", "exact"),
                new ProductDiscoveryPolicyDecisionDetails(
                        ProductDiscoveryPolicyDecisionDetails.EVALUATION_REQUEST,
                        List.of("GB"),
                        List.of("SECRET"),
                        List.of("name"),
                        List.of("internal_owner"),
                        List.of("contact_email")));

        assertThat(discoverLogAfter(mockMvc, published))
                .contains("allow=true")
                .contains("policy=product.discover")
                .contains("resolution=exact")
                .contains("evaluation=request")
                .contains("permittedNationalities=[GB]")
                .contains("excludedClassifications=[SECRET]")
                .contains("allowed=[name]")
                .contains("denied=[internal_owner]")
                .contains("masked=[contact_email]");
    }

    @Test
    void discover_withPolicySwitchedOff_logsThatNoDecisionWasTaken() throws Exception {
        assertThat(discoverLogAfter(mockMvc(false), null)).contains("without a policy decision");
    }

    // ---------------------------------------------------------------------------------------
    // Subscribe
    // ---------------------------------------------------------------------------------------

    private static PolicyDecision<ProductSubscriptionPolicyDecisionDetails> subscribeDecision(
            Boolean requiresApproval) {
        return PolicyDecision.of(true, ProductSubscriptionPolicyDecisionDetails.class)
                .withDetails(new ProductSubscriptionPolicyDecisionDetails(
                        requiresApproval, 90, List.of("cron", "interval")));
    }

    private static final String SUBSCRIPTION =
            """
            {"productId": 42, "scheduleType": "cron", "scheduleExpression": "*/5 * * * *"}""";

    @Test
    void subscribe_withoutApprovalRequired_isAcceptedOnThePolicyTerms() throws Exception {
        mockMvc.perform(post("/api/v1/product/subscribe")
                        .requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, subscribeDecision(false))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIPTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(42))
                .andExpect(jsonPath("$.status").value(ProductSubscriptionResponseDTO.STATUS_ACCEPTED))
                .andExpect(jsonPath("$.maxValidityDays").value(90))
                .andExpect(jsonPath("$.permittedScheduleTypes[0]").value("cron"));
    }

    @Test
    void subscribe_whenPolicyRequiresApproval_isPendingApproval() throws Exception {
        mockMvc.perform(post("/api/v1/product/subscribe")
                        .requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, subscribeDecision(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIPTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(ProductSubscriptionResponseDTO.STATUS_PENDING_APPROVAL));
    }

    /** A missing approval flag is not read as "no approval needed". */
    @Test
    void subscribe_whenPolicyOmitsTheApprovalFlag_isPendingApproval() throws Exception {
        mockMvc.perform(post("/api/v1/product/subscribe")
                        .requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, subscribeDecision(null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIPTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(ProductSubscriptionResponseDTO.STATUS_PENDING_APPROVAL));
    }

    /** With policy switched off nothing set terms, so the request is held rather than accepted. */
    @Test
    void subscribe_withPolicySwitchedOff_isPendingApprovalWithNoTerms() throws Exception {
        mockMvc(false)
                .perform(post("/api/v1/product/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIPTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(ProductSubscriptionResponseDTO.STATUS_PENDING_APPROVAL))
                .andExpect(jsonPath("$.maxValidityDays").doesNotExist())
                .andExpect(jsonPath("$.permittedScheduleTypes").isEmpty());
    }

    @Test
    void subscribe_withoutProductId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/product/subscribe")
                        .requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, subscribeDecision(false))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleType\": \"cron\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------------------------
    // View
    // ---------------------------------------------------------------------------------------

    @Test
    void view_existingProduct_isReturned() throws Exception {
        when(productService.getProductsByIds(List.of(7L)))
                .thenReturn(
                        List.of(ProductDTO.builder().id(7L).name("Product A").build()));

        mockMvc.perform(get("/api/v1/product/7")
                        .requestAttr(
                                PolicyDecision.REQUEST_ATTRIBUTE,
                                PolicyDecision.of(true, ProductViewPolicyDecisionDetails.class)
                                        .withDetails(new ProductViewPolicyDecisionDetails(
                                                ProductViewPolicyDecisionDetails.ACCESS_LEVEL_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Product A"));
    }

    @Test
    void view_withPolicySwitchedOff_isStillServed() throws Exception {
        when(productService.getProductsByIds(List.of(7L)))
                .thenReturn(
                        List.of(ProductDTO.builder().id(7L).name("Product A").build()));

        mockMvc(false).perform(get("/api/v1/product/7")).andExpect(status().isOk());
    }

    @Test
    void view_unknownProduct_returns404() throws Exception {
        when(productService.getProductsByIds(List.of(99L))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/product/99")).andExpect(status().isNotFound());
    }
}
