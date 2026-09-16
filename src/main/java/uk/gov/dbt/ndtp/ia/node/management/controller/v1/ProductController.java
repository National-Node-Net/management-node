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
import java.util.List;
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
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductSubscriptionRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductSubscriptionResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductSubscriptionPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductViewPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.Policy;

@RestController
@RequestMapping("/api/v1/product")
@Slf4j
@Tag(name = "Product", description = "Policy-aware discovery, viewing and subscription of data products.")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/discover")
    @PreAuthorize("hasAuthority('ROLE_management-node:product_discovery')")
    @Policy(resource = "product", action = "discover", details = ProductDiscoveryPolicyDecisionDetails.class)
    @Operation(
            summary = "Discover authorised products",
            description = "Accepts a free-text term and a set of named filters. The product.discover policy "
                    + "decides whether the caller may discover at all. NOT YET IMPLEMENTED: candidate lookup "
                    + "and per-product policy filtering are not wired up, so the product list is always empty.",
            security = {@SecurityRequirement(name = "bearerAuth")})
    @ApiResponse(
            responseCode = "200",
            description = "Discovery response returned; the product list is currently always empty",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProductDiscoveryResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden by role or by policy")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    public ProductDiscoveryResponseDTO discoverProducts(
            @Valid @RequestBody(required = false) ProductDiscoveryRequestDTO criteria,
            @Parameter(hidden = true) Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>> policyDecision) {
        // The request-level decision gates whether this caller may discover at all. Which products
        // they see is a separate, per-candidate decision from the same rule, so this one is what
        // the discovery service narrows once candidate lookup is wired up.
        policyDecision.ifPresentOrElse(
                decision -> log.info(
                        "Product discover policy decision allow={} policy={} resolution={} reasons={} masked={} "
                                + "evaluation={} permittedNationalities={} excludedClassifications={}",
                        decision.allow(),
                        decision.policy().id(),
                        decision.policy().resolution(),
                        decision.reasons(),
                        decision.maskedFilteredAttributes(),
                        decision.details().evaluation(),
                        decision.details().permittedNationalities(),
                        decision.details().excludedClassifications()),
                () -> log.info("Product discover served without a policy decision (policy enforcement is off)"));
        return ProductDiscoveryResponseDTO.builder().build();
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
            description = "Returns a single product. No rule is dedicated to viewing, so the product "
                    + "resource fallback decides: read-only access for a caller from a known organisation.",
            security = {@SecurityRequirement(name = "bearerAuth")})
    @ApiResponse(
            responseCode = "200",
            description = "Product returned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden by role or by policy")
    @ApiResponse(responseCode = "404", description = "No product with that identifier")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    public ResponseEntity<ProductDTO> viewProduct(
            @Parameter(description = "Product identifier") @PathVariable("productId") Long productId,
            @Parameter(hidden = true) Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> policyDecision) {
        policyDecision.ifPresentOrElse(
                decision -> log.debug(
                        "Product view policy decision allow={} policy={} resolution={} reasons={} accessLevel={}",
                        decision.allow(),
                        decision.policy().id(),
                        decision.policy().resolution(),
                        decision.reasons(),
                        decision.details().accessLevel()),
                () -> log.debug("Product view served without a policy decision (policy enforcement is off)"));
        return productService.getProductsByIds(List.of(productId)).stream()
                .findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
