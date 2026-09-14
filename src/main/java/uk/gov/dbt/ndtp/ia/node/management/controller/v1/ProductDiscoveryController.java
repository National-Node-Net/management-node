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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.dbt.ndtp.ia.node.management.config.RequestRejectionSupport;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProductDiscoveryRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProductDiscoveryResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductDiscoveryService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyRequester;

@RestController
@RequestMapping("/api/v1/product")
@Slf4j
@Tag(
        name = "Product Discovery",
        description = "Policy-aware discovery of data products the requester is authorised to see.")
public class ProductDiscoveryController {

    private final ProductDiscoveryService productDiscoveryService;

    public ProductDiscoveryController(ProductDiscoveryService productDiscoveryService) {
        this.productDiscoveryService = productDiscoveryService;
    }

    @PostMapping("/discovery")
    @PreAuthorize("hasAuthority('ROLE_management-node:discover_products')")
    @Operation(
            summary = "Discover authorised products",
            description = "Returns only the products the authenticated requester is authorised to discover, "
                    + "narrowed by the supplied search criteria. Never returns products denied by policy, "
                    + "even if they match the search criteria.",
            security = {@SecurityRequirement(name = "bearerAuth")})
    @ApiResponse(
            responseCode = "200",
            description = "Discovery response returned (possibly with an empty product list)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProductDiscoveryResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    public ProductDiscoveryResponseDTO discoverProducts(
            @Parameter(hidden = true) @AuthenticationPrincipal EnhancedPrincipal principal,
            HttpServletRequest request,
            @Valid @RequestBody(required = false) ProductDiscoveryRequestDTO criteria) {
        ProductDiscoveryRequestDTO effectiveCriteria = criteria != null
                ? criteria
                : ProductDiscoveryRequestDTO.builder().build();
        PolicyRequester requester = new PolicyRequester(
                principal.clientId(), principal.organisation(), RequestRejectionSupport.getOrganisationId(request));

        log.info(
                "Product discovery request clientId={} organisation={} organisationId={} name={} topic={} type={}",
                requester.clientId(),
                requester.organisation(),
                requester.organisationId(),
                effectiveCriteria.name(),
                effectiveCriteria.topic(),
                effectiveCriteria.type());

        return productDiscoveryService.discover(
                requester, effectiveCriteria.name(), effectiveCriteria.topic(), effectiveCriteria.type());
    }
}
