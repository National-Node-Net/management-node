/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.DefaultPolicyDecisionOutput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInputFactory;

@RestController
@RequestMapping("/api/v1/product")
@Slf4j
@Tag(
        name = "Product Discovery",
        description = "Policy-aware discover of data products the requester is authorised to see.")
public class ProductController {

    private final PolicyInputFactory policyInputFactory;

    public ProductController(PolicyInputFactory policyInputFactory) {
        this.policyInputFactory = policyInputFactory;
    }

    @PostMapping("/discover")
    @PreAuthorize("hasAuthority('ROLE_management-node:product_discovery')")
    @Operation(
            summary = "Discover authorised products",
            description = "Accepts a free-text term and a set of named filters. NOT YET IMPLEMENTED: the "
                    + "criteria are validated and recorded, and the policy decision for the request is "
                    + "resolved, but candidate lookup and per-product policy filtering are not wired up, "
                    + "so the product list is always empty.",
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
    @ApiResponse(responseCode = "403", description = "policyDecision = {DefaultPolicyDecisionOutput@24571} \"DefaultPolicyDecisionOutput[allow=true, allowedFilteredAttributes=[], deniedFilteredAttributes=[], maskedFilteredAttributes=[]]\"Forbidden")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    public ProductDiscoveryResponseDTO discoverProducts(
            HttpServletRequest request,
            @Valid @RequestBody(required = false) ProductDiscoveryRequestDTO criteria,
            DefaultPolicyDecisionOutput policyDecision) {
        // An absent body is no criteria rather than no request, so it reaches policy as an empty
        // criteria document instead of a null the PDP would have to interpret.
        ProductDiscoveryRequestDTO effectiveCriteria =
                criteria == null ? ProductDiscoveryRequestDTO.builder().build() : criteria;
        // The bound criteria are passed to the factory rather than the request stream: the body
        // has already been consumed by binding, and policy should see the criteria the endpoint
        // will actually act on.
        PolicyInput input = policyInputFactory.create(request, effectiveCriteria);
        // policyDecision is resolved by PolicyDecisionOutputArgumentResolver from the decision the
        // Policy Enforcement Point published for this request, or ALLOW with nothing filtered
        // where no whole-request decision was taken. Both it and the input are what get handed to
        // ProductDiscoveryService once candidate lookup is wired back up.
        log.info(
                "Product discover request action={} criteria={} policyAllow={}",
                input.action(),
                effectiveCriteria,
                policyDecision.allow());
        return ProductDiscoveryResponseDTO.builder().build();
    }
}
