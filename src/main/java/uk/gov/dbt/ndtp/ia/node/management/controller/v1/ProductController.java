/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.DiscoveredProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductSubscriptionRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductSubscriptionResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductSubscriptionPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductViewPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductDiscoveryService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductViewService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.Policy;

@RestController
@RequestMapping("/api/v1/product")
@Slf4j
@Tag(name = "Product", description = "Policy-aware discovery, viewing and subscription of data products.")
public class ProductController {

    private final ProductDiscoveryService productDiscoveryService;

    private final ProductViewService productViewService;

    public ProductController(ProductDiscoveryService productDiscoveryService, ProductViewService productViewService) {
        this.productDiscoveryService = productDiscoveryService;
        this.productViewService = productViewService;
    }

    @PostMapping("/discover")
    @PreAuthorize("hasAuthority('ROLE_management-node:product_discovery')")
    @Policy(resource = "product", action = "discover", details = ProductDiscoveryPolicyDecisionDetails.class)
    @Operation(
            summary = "Discover authorised products",
            description = "Searches data products by free text, filters on product fields and policy "
                    + "attributes, sorting and paging. Each result is a summary - id, name, description, type "
                    + "and the owning organisation's key and name - enough to recognise a product, see "
                    + "whose it is, and ask for it by id; the rest of a "
                    + "product is returned by GET /api/v1/product/{productId}. Filtering and sorting are not "
                    + "limited to those four: a search may filter and sort on anything policy allows it, "
                    + "including fields the summary does not carry. The product.discover policy decides "
                    + "whether the caller may discover at all, which products exist for them, what they may "
                    + "filter on, and what is withheld. An empty body returns the first page of everything "
                    + "the caller may discover. With policy enforcement switched off, nothing is restricted "
                    + "and the response carries no policy block.",
            security = {@SecurityRequirement(name = "bearerAuth")})
    @ApiResponse(
            responseCode = "200",
            description = "The page of product summaries the caller may discover, including when it is empty",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProductDiscoveryResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Malformed search criteria")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden by role or by policy")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    public ProductDiscoveryResponseDTO discoverProducts(
            @Valid @RequestBody(required = false) ProductDiscoveryRequestDTO criteria,
            @Parameter(hidden = true) Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>> policyDecision) {
        // The decision taken for this request is the only one there is: it carries the caller's
        // search contract, which the service turns into the query. It is passed on rather than
        // asked for again, so nothing can answer differently half way through a search.
        policyDecision.ifPresent(decision -> log.debug(
                "Product discover policy decision allow={} policy={} resolution={} reasons={} evaluation={}",
                decision.allow(),
                decision.policy().id(),
                decision.policy().resolution(),
                decision.reasons(),
                decision.details().evaluation()));
        return productDiscoveryService.discover(criteria, policyDecision);
    }

    @PostMapping("/subscribe")
    @PreAuthorize("hasAuthority('ROLE_management-node:product_subscribe')")
    @Policy(resource = "product", action = "subscribe", details = ProductSubscriptionPolicyDecisionDetails.class)
    @Operation(
            summary = "Subscribe to a product",
            description = "Requests a subscription of the caller's organisation to a product. The "
                    + "product.subscribe policy decides whether the request is allowed and sets its terms: "
                    + "whether approval is required, the maximum validity and the permitted schedule types.",
            security = {@SecurityRequirement(name = "bearerAuth")})
    @ApiResponse(
            responseCode = "200",
            description = "Subscription accepted or awaiting approval, with the terms policy set",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProductSubscriptionResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden by role or by policy")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    public ProductSubscriptionResponseDTO subscribe(
            @Valid @RequestBody ProductSubscriptionRequestDTO subscription,
            @Parameter(hidden = true)
                    Optional<PolicyDecision<ProductSubscriptionPolicyDecisionDetails>> policyDecision) {
        // Terms only exist when policy decided them. With policy switched off nothing has judged
        // the request, so it is held for approval rather than accepted on no one's authority.
        return policyDecision
                .map(PolicyDecision::details)
                .map(terms -> ProductSubscriptionResponseDTO.builder()
                        .productId(subscription.productId())
                        .status(
                                Boolean.FALSE.equals(terms.requiresApproval())
                                        ? ProductSubscriptionResponseDTO.STATUS_ACCEPTED
                                        : ProductSubscriptionResponseDTO.STATUS_PENDING_APPROVAL)
                        .maxValidityDays(terms.maxValidityDays())
                        .permittedScheduleTypes(terms.permittedScheduleTypes())
                        .build())
                .orElseGet(() -> ProductSubscriptionResponseDTO.builder()
                        .productId(subscription.productId())
                        .status(ProductSubscriptionResponseDTO.STATUS_PENDING_APPROVAL)
                        .build());
    }

    @GetMapping("/{productId}")
    @PreAuthorize("hasAuthority('ROLE_management-node:product_view')")
    @Policy(resource = "product", action = "view", details = ProductViewPolicyDecisionDetails.class)
    @Operation(
            summary = "View a product",
            description = "Returns one product, as much of it as policy allows. The product.view policy "
                    + "decides whether the caller may view at all, which products exist for them, and what is "
                    + "withheld from the result - the same contract discovery is given, so a product the "
                    + "caller could not discover cannot be reached by its id either. A product the caller "
                    + "may not see is answered 404, exactly as a product that does not exist.",
            security = {@SecurityRequirement(name = "bearerAuth")})
    @ApiResponse(
            responseCode = "200",
            description = "The product, with the members policy withholds absent",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = DiscoveredProductDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden by role or by policy")
    @ApiResponse(responseCode = "404", description = "No such product for this caller")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    public ResponseEntity<DiscoveredProductDTO> viewProduct(
            @Parameter(description = "Product identifier") @PathVariable("productId") Long productId,
            @Parameter(hidden = true) Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> policyDecision) {
        policyDecision.ifPresent(decision -> log.debug(
                "Product view policy decision allow={} policy={} resolution={} reasons={} accessLevel={}",
                decision.allow(),
                decision.policy().id(),
                decision.policy().resolution(),
                decision.reasons(),
                decision.details().accessLevel()));
        return productViewService
                .view(productId, policyDecision)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
