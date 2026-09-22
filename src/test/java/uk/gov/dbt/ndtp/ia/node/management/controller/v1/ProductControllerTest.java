/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.InvalidSearchCriteriaException;
import uk.gov.dbt.ndtp.ia.node.management.exception.SubscriptionRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.handlers.GlobalExceptionHandler;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.DiscoveredProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductSubscriptionResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductSubscriptionPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductViewPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductDiscoveryService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductSubscriptionService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductViewService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyProvenance;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyDecisionArgumentResolver;

/**
 * Covers the product endpoints at the controller boundary.
 *
 * <p>{@code POST /api/v1/product/discover} is covered here as far as the controller's own job goes:
 * binding and validating the criteria, handing the request and the decision to
 * {@link ProductDiscoveryService}, and answering with what the service returned. What the service
 * makes of them - the contract, the query and the response - is {@code ProductDiscoveryServiceImplTest}.
 *
 * <p>The PEP is not part of a standalone MockMvc setup, so the subscribe and view tests publish a
 * decision on the request themselves - exactly what the PEP does before the handler runs - and
 * check how the handler uses it.
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private ProductDiscoveryService productDiscoveryService;

    @Mock
    private ProductViewService productViewService;

    @Mock
    private ProductSubscriptionService productSubscriptionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvc(true);
        authenticateAs("client-1");
    }

    private MockMvc mockMvc(boolean opaEnabled) {
        return MockMvcBuilders.standaloneSetup(
                        new ProductController(productDiscoveryService, productViewService, productSubscriptionService))
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

    /** What the service answers unless a test says otherwise. */
    private void serviceReturns(ProductDiscoveryResponseDTO response) {
        when(productDiscoveryService.discover(any(), any())).thenReturn(response);
    }

    private static ProductDiscoveryResponseDTO oneProduct() {
        return ProductDiscoveryResponseDTO.builder()
                .products(List.of(DiscoveredProductDTO.builder()
                        .id(3L)
                        .name("FloodRiskMapZones")
                        .build()))
                .page(ProductDiscoveryResponseDTO.Page.of(0, 20, 1, 1))
                .build();
    }

    @Test
    void discover_criteria_areBoundAndPassedToTheService() throws Exception {
        serviceReturns(oneProduct());

        mockMvc.perform(
                        post("/api/v1/product/discover")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"text": "flood",
                                 "filters": [{"field": "type", "operator": "eq", "values": ["topic"]},
                                             {"scope": "organisation", "attribute": "jurisdictions",
                                              "operator": "any_of", "values": ["England"]}],
                                 "sort": [{"field": "name", "direction": "desc"}],
                                 "page": 2, "size": 5}"""))
                .andExpect(status().isOk());

        ArgumentCaptor<ProductDiscoveryRequestDTO> criteria = ArgumentCaptor.forClass(ProductDiscoveryRequestDTO.class);
        verify(productDiscoveryService).discover(criteria.capture(), any());
        ProductDiscoveryRequestDTO sent = criteria.getValue();
        assertThat(sent.text()).isEqualTo("flood");
        assertThat(sent.page()).isEqualTo(2);
        assertThat(sent.size()).isEqualTo(5);
        assertThat(sent.filters()).hasSize(2);
        assertThat(sent.filters().get(0).field()).isEqualTo("type");
        assertThat(sent.filters().get(0).operator()).isEqualTo(ComparisonOperator.EQ);
        assertThat(sent.filters().get(1).scope()).isEqualTo(FilterScope.ORGANISATION);
        assertThat(sent.filters().get(1).attribute()).isEqualTo("jurisdictions");
        assertThat(sent.sort()).singleElement().satisfies(key -> {
            assertThat(key.field()).isEqualTo("name");
            assertThat(key.descending()).isTrue();
        });
    }

    @Test
    void discover_serviceResponse_isReturnedAsIs() throws Exception {
        serviceReturns(oneProduct());

        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].id").value(3))
                .andExpect(jsonPath("$.products[0].name").value("FloodRiskMapZones"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                // A member the service did not set is absent, not null: policy withheld it.
                .andExpect(jsonPath("$.products[0].source").doesNotExist())
                .andExpect(jsonPath("$.policy").doesNotExist());
    }

    @Test
    void discover_emptyBody_reachesTheServiceAsNoCriteria() throws Exception {
        serviceReturns(ProductDiscoveryResponseDTO.builder()
                .page(ProductDiscoveryResponseDTO.Page.of(0, 20, 0, 0))
                .build());

        mockMvc.perform(post("/api/v1/product/discover").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").isEmpty());

        verify(productDiscoveryService).discover(isNull(), any());
    }

    /** An unknown top-level field is dropped at binding rather than rejected, as before. */
    @Test
    void discover_unknownTopLevelField_isIgnored() throws Exception {
        serviceReturns(oneProduct());

        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"planning\",\"fitlers\":[]}"))
                .andExpect(status().isOk());
    }

    @Test
    void discover_oversizedText_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productDiscoveryService);
    }

    @Test
    void discover_tooManyFilters_returns400() throws Exception {
        String filters = IntStream.rangeClosed(0, 50)
                .mapToObj(i -> "{\"field\":\"name\",\"values\":[\"v" + i + "\"]}")
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":[" + filters + "]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productDiscoveryService);
    }

    @Test
    void discover_oversizedFilterName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":[{\"attribute\":\"" + "k".repeat(151) + "\",\"values\":[\"v\"]}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void discover_negativePage_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void discover_unknownOperator_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"filters\":[{\"field\":\"name\",\"operator\":\"sounds_like\",\"values\":[\"a\"]}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void discover_malformedJsonBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }

    /** Criteria the service cannot act on are the caller's to correct, so they are a 400. */
    @Test
    void discover_invalidCriteria_returns400WithTheReason() throws Exception {
        when(productDiscoveryService.discover(any(), any()))
                .thenThrow(new InvalidSearchCriteriaException("A filter names exactly one of 'field' and 'attribute'"));

        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":[{\"field\":\"name\",\"attribute\":\"x\",\"values\":[\"v\"]}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("exactly one")));
    }

    /** Criteria policy does not permit are a 403 naming what was refused, not a 400. */
    @Test
    void discover_criteriaRefusedByPolicy_returns403WithNamedReasons() throws Exception {
        when(productDiscoveryService.discover(any(), any()))
                .thenThrow(new AccessRejectedException(
                        "Access denied by policy",
                        List.of("filter.attribute_not_permitted:identifiability"),
                        "error-1"));

        mockMvc.perform(post("/api/v1/product/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":[{\"attribute\":\"identifiability\",\"values\":[\"non_personal\"]}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied by policy"))
                .andExpect(jsonPath("$.reasons[0]").value("filter.attribute_not_permitted:identifiability"));
    }

    // ---------------------------------------------------------------------------------------
    // Discover: the request-level decision reaches the handler and is handed to the service
    // ---------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>> decisionHandedToService(
            MockMvc mvc, PolicyDecision<?> published) throws Exception {
        serviceReturns(oneProduct());
        var request = post("/api/v1/product/discover")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"search term\"}");
        if (published != null) {
            request.requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, published);
        }
        mvc.perform(request).andExpect(status().isOk());

        ArgumentCaptor<Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>>> decision =
                ArgumentCaptor.forClass(Optional.class);
        verify(productDiscoveryService).discover(any(), decision.capture());
        return decision.getValue();
    }

    @Test
    void discover_publishedDecision_isHandedToTheService() throws Exception {
        PolicyDecision<ProductDiscoveryPolicyDecisionDetails> published = new PolicyDecision<>(
                true,
                List.of(),
                new PolicyProvenance("product.discover", "policies.product.discover/3.0.0", "exact"),
                new ProductDiscoveryPolicyDecisionDetails(
                        ProductDiscoveryPolicyDecisionDetails.EVALUATION_REQUEST,
                        new ProductDiscoveryPolicyDecisionDetails.Filtering(
                                List.of("name", "topic"), List.of(), List.of("consumers")),
                        new ProductDiscoveryPolicyDecisionDetails.Filtering(
                                List.of("identifiability"), List.of(), List.of("population_risk_tags"))));

        assertThat(decisionHandedToService(mockMvc, published)).containsSame(published);
    }

    /**
     * With policy enforcement off no decision is taken, and the handler passes on the empty value
     * rather than standing in a verdict of its own: the service then searches unrestricted.
     */
    @Test
    void discover_withPolicySwitchedOff_handsTheServiceNoDecision() throws Exception {
        assertThat(decisionHandedToService(mockMvc(false), null)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // Subscribe
    // ---------------------------------------------------------------------------------------

    private static PolicyDecision<ProductSubscriptionPolicyDecisionDetails> subscribeDecision(
            Boolean requiresApproval) {
        return PolicyDecision.of(true, ProductSubscriptionPolicyDecisionDetails.class)
                .withDetails(new ProductSubscriptionPolicyDecisionDetails(
                        requiresApproval, 90, List.of("cron", "interval"), 90));
    }

    private static final String SUBSCRIPTION =
            """
            {"productId": 42, "scheduleType": "cron", "scheduleExpression": "*/5 * * * *"}""";

    private static ProductSubscriptionResponseDTO recorded() {
        return ProductSubscriptionResponseDTO.builder()
                .subscriptionId(7L)
                .productId(42L)
                .consumerId(4L)
                .consumerName("consumer-a")
                .validityDays(90)
                .maxValidityDays(90)
                .scheduleType("cron")
                .scheduleExpression("*/5 * * * *")
                .permittedScheduleTypes(List.of("cron", "interval"))
                .build();
    }

    /**
     * The controller does not decide the outcome: it hands the request, the caller's organisation
     * and the decision to the service and returns what comes back.
     */
    @Test
    void subscribe_delegatesToTheServiceAndReturnsWhatItRecorded() throws Exception {
        when(productSubscriptionService.subscribe(any(), any(), any())).thenReturn(recorded());

        mockMvc.perform(post("/api/v1/product/subscribe")
                        .requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, subscribeDecision(false))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIPTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionId").value(7))
                .andExpect(jsonPath("$.productId").value(42))
                .andExpect(jsonPath("$.consumerId").value(4))
                .andExpect(jsonPath("$.validityDays").value(90))
                .andExpect(jsonPath("$.permittedScheduleTypes[0]").value("cron"));
    }

    /** The decision the enforcement point took is the one the service is given. */
    @Test
    void subscribe_passesThePolicyDecisionToTheService() throws Exception {
        when(productSubscriptionService.subscribe(any(), any(), any())).thenReturn(recorded());
        PolicyDecision<ProductSubscriptionPolicyDecisionDetails> decision = subscribeDecision(true);

        mockMvc.perform(post("/api/v1/product/subscribe")
                        .requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, decision)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIPTION))
                .andExpect(status().isOk());

        ArgumentCaptor<Optional<PolicyDecision<ProductSubscriptionPolicyDecisionDetails>>> captor =
                ArgumentCaptor.forClass(Optional.class);
        verify(productSubscriptionService).subscribe(any(), any(), captor.capture());
        assertThat(captor.getValue()).containsSame(decision);
    }

    /** A subscription carries no status: it is recorded or the request fails. */
    @Test
    void subscribe_responseCarriesNoStatusField() throws Exception {
        when(productSubscriptionService.subscribe(any(), any(), any())).thenReturn(recorded());

        mockMvc.perform(post("/api/v1/product/subscribe")
                        .requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, subscribeDecision(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIPTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.subscriptionId").value(7));
    }

    /** With policy switched off the service is still called, and told that nothing decided. */
    @Test
    void subscribe_withPolicySwitchedOff_handsTheServiceAnEmptyDecision() throws Exception {
        when(productSubscriptionService.subscribe(any(), any(), any())).thenReturn(recorded());

        mockMvc(false)
                .perform(post("/api/v1/product/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIPTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").doesNotExist());

        ArgumentCaptor<Optional<PolicyDecision<ProductSubscriptionPolicyDecisionDetails>>> captor =
                ArgumentCaptor.forClass(Optional.class);
        verify(productSubscriptionService).subscribe(any(), any(), captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    /** A subscription that cannot be made is a 409, distinct from a policy refusal's 403. */
    @Test
    void subscribe_whenAlreadySubscribed_returns409WithTheMessage() throws Exception {
        when(productSubscriptionService.subscribe(any(), any(), any()))
                .thenThrow(new SubscriptionRejectedException(
                        SubscriptionRejectedException.Reason.ALREADY_SUBSCRIBED,
                        "Organisation 'ENV' is already subscribed to product 42 on consumer 'a' (id 4)"));

        mockMvc.perform(post("/api/v1/product/subscribe")
                        .requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, subscribeDecision(false))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIPTION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already subscribed")))
                .andExpect(jsonPath("$.reasons").doesNotExist());
    }

    /** Several consumers and none named is the caller's to fix, so 400 rather than 409. */
    @Test
    void subscribe_whenSeveralConsumersAndNoneNamed_returns400() throws Exception {
        when(productSubscriptionService.subscribe(any(), any(), any()))
                .thenThrow(new SubscriptionRejectedException(
                        SubscriptionRejectedException.Reason.AMBIGUOUS_CONSUMER,
                        "Multiple consumers found, please specify consumer_id"));

        mockMvc.perform(post("/api/v1/product/subscribe")
                        .requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, subscribeDecision(false))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIPTION))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Multiple consumers found")));
    }

    /** An unknown product is a 404, as it is on the view endpoint. */
    @Test
    void subscribe_whenProductDoesNotExist_returns404() throws Exception {
        when(productSubscriptionService.subscribe(any(), any(), any()))
                .thenThrow(new SubscriptionRejectedException(
                        SubscriptionRejectedException.Reason.PRODUCT_NOT_FOUND, "No such product: 42"));

        mockMvc.perform(post("/api/v1/product/subscribe")
                        .requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, subscribeDecision(false))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIPTION))
                .andExpect(status().isNotFound());
    }

    @Test
    void subscribe_withoutProductId_returns400NamingTheFieldWithoutCodeDetails() throws Exception {
        mockMvc.perform(post("/api/v1/product/subscribe")
                        .requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, subscribeDecision(false))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleType\": \"cron\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid request: productId must not be null"))
                .andExpect(jsonPath("$.errorId").isNotEmpty());
    }

    // ---------------------------------------------------------------------------------------
    // View: a search constrained to one product
    // ---------------------------------------------------------------------------------------

    private static PolicyDecision<ProductViewPolicyDecisionDetails> viewDecision() {
        return PolicyDecision.of(true, ProductViewPolicyDecisionDetails.class)
                .withDetails(new ProductViewPolicyDecisionDetails(ProductViewPolicyDecisionDetails.ACCESS_LEVEL_READ));
    }

    /** What the view service answers; a null product stands for "no such product for this caller". */
    private void viewReturns(DiscoveredProductDTO product) {
        when(productViewService.view(any(), any())).thenReturn(Optional.ofNullable(product));
    }

    @Test
    void view_existingProduct_isReturnedWithWhatPolicyLeftVisible() throws Exception {
        viewReturns(DiscoveredProductDTO.builder()
                .id(7L)
                .name("FloodRiskMapZones")
                .organisation(
                        DiscoveredProductDTO.Organisation.builder().key("ENV").build())
                .build());

        mockMvc.perform(get("/api/v1/product/7").requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, viewDecision()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("FloodRiskMapZones"))
                .andExpect(jsonPath("$.organisation.key").value("ENV"))
                // Masking shows as absence, not as null: a member the service never set is not
                // serialised at all, so a caller cannot tell "withheld" from "we sent you nothing".
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.source").doesNotExist())
                .andExpect(jsonPath("$.consumers").doesNotExist())
                .andExpect(jsonPath("$.organisation.name").doesNotExist());
    }

    /**
     * An empty answer is a 404 whether the product does not exist or the row filter excluded it.
     * The API deliberately does not distinguish the two (D1): a 403 for "excluded" would turn an id
     * into an oracle for the existence of products the caller may not discover, and the service
     * hands back the same {@code Optional.empty()} for both, so the controller could not tell them
     * apart even if it wanted to.
     */
    @Test
    void view_noProductForThisCaller_returns404WhetherItIsAbsentOrWithheld() throws Exception {
        viewReturns(null);

        mockMvc.perform(get("/api/v1/product/99").requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, viewDecision()))
                .andExpect(status().isNotFound());

        ArgumentCaptor<Long> productId = ArgumentCaptor.forClass(Long.class);
        verify(productViewService).view(productId.capture(), any());
        assertThat(productId.getValue()).isEqualTo(99L);
    }

    @Test
    void view_pathVariable_reachesTheServiceAsALong() throws Exception {
        viewReturns(DiscoveredProductDTO.builder().id(7L).build());

        mockMvc.perform(get("/api/v1/product/7").requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, viewDecision()))
                .andExpect(status().isOk());

        ArgumentCaptor<Long> productId = ArgumentCaptor.forClass(Long.class);
        verify(productViewService).view(productId.capture(), any());
        assertThat(productId.getValue()).isEqualTo(7L);
    }

    /** Type conversion, not policy: the id never binds, so nothing is asked of the service. */
    @Test
    void view_nonNumericProductId_returns400WithoutReachingTheService() throws Exception {
        mockMvc.perform(get("/api/v1/product/not-a-number")
                        .requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, viewDecision()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productViewService);
    }

    @SuppressWarnings("unchecked")
    private Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> viewDecisionHandedToService(
            MockMvc mvc, PolicyDecision<?> published) throws Exception {
        viewReturns(DiscoveredProductDTO.builder().id(7L).build());
        var request = get("/api/v1/product/7");
        if (published != null) {
            request.requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, published);
        }
        mvc.perform(request).andExpect(status().isOk());

        ArgumentCaptor<Optional<PolicyDecision<ProductViewPolicyDecisionDetails>>> decision =
                ArgumentCaptor.forClass(Optional.class);
        verify(productViewService).view(any(), decision.capture());
        return decision.getValue();
    }

    /** The decision the PEP published is the one the service works from - it asks for no other. */
    @Test
    void view_publishedDecision_isHandedToTheService() throws Exception {
        PolicyDecision<ProductViewPolicyDecisionDetails> published = viewDecision();

        assertThat(viewDecisionHandedToService(mockMvc, published)).containsSame(published);
    }

    /**
     * With policy enforcement off nothing judged the request, and the handler passes the empty
     * value on rather than inventing a verdict: the service is the one that decides what an absent
     * decision means.
     */
    @Test
    void view_withPolicySwitchedOff_handsTheServiceNoDecision() throws Exception {
        assertThat(viewDecisionHandedToService(mockMvc(false), null)).isEmpty();
    }

    /** A contract the service cannot honour is a 403 naming why, exactly as on discovery. */
    @Test
    void view_refusedByTheService_returns403WithItsReasons() throws Exception {
        when(productViewService.view(any(), any()))
                .thenThrow(new AccessRejectedException(
                        "Access denied by policy", List.of("policy.obligation_unsupported"), "error-2"));

        mockMvc.perform(get("/api/v1/product/7").requestAttr(PolicyDecision.REQUEST_ATTRIBUTE, viewDecision()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied by policy"))
                .andExpect(jsonPath("$.reasons[0]").value("policy.obligation_unsupported"));
    }
}
