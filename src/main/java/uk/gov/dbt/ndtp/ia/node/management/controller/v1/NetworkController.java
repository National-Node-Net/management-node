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
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.network.NetworkMapDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.data.NetworkMapService;

/**
 * The network's topology: who is on it, and what joins them.
 *
 * <h2>This endpoint is not policy-enforced, and that is a decision worth reading</h2>
 *
 * It carries no {@code @Policy} annotation, so the PDP is never consulted for it and no search
 * contract shapes the result. It returns the whole network to anyone holding the role. That is a
 * deliberate departure from {@code /api/v1/product/**}, where the decision <em>is</em> the query,
 * and the consequences should be stated rather than discovered:
 *
 * <ul>
 *   <li><b>What it exposes is topology, not content.</b> Names, ids and the relationships between
 *       them - no policy attributes, no classifications, no free-text beyond the descriptions the
 *       catalogue already publishes. It answers "who exchanges data with whom", not "what is in
 *       it".
 *   <li><b>It is therefore wider than discovery.</b> A caller sees organisations, producers and
 *       products that {@code product.discover} would withhold from them. Anyone for whom that
 *       matters must not be given this role.
 *   <li><b>It is not a way around the product API.</b> Reading a product's attributes still means
 *       {@code GET /api/v1/product/{productId}}, which <em>is</em> policy-enforced and answers
 *       {@code 404} for a product the caller may not see. An id from this map does not unlock it.
 * </ul>
 *
 * If that trade stops being acceptable, the change is to annotate this method with
 * {@code @Policy(resource = "network", action = "map", ...)} and write the rule - the enforcement
 * machinery needs nothing else, since it acts on the annotation. Until then the guard is
 * authentication plus the role below, and nothing more.
 */
@RestController
@RequestMapping("/api/v1/network")
@Slf4j
@Tag(
        name = "Network",
        description =
                "The network's topology: organisations, their producers and consumers, the products published, and the subscriptions between them.")
public class NetworkController {

    private final NetworkMapService networkMapService;

    public NetworkController(NetworkMapService networkMapService) {
        this.networkMapService = networkMapService;
    }

    /**
     * The whole network in one request.
     *
     * The alternative a client has is to call {@code product/discover} and then {@code product/{id}}
     * once per product, which is what the console did before this existed: one request per product,
     * and a topology assembled by inference from what each view happened to carry. One statement
     * per entity kind here replaces N+1 round trips, and the result is a consistent snapshot rather
     * than a set of reads taken at different moments.
     *
     * Reuses {@code product_discovery} rather than introducing a role of its own. A new role would
     * have to be created in the realm and granted to every existing caller before anything worked,
     * and this endpoint is read-only catalogue topology - the same broad permission that role
     * already names. A deployment wanting it held separately should add its own role here and in
     * {@code docs/AUTHENTICATION_REQUIREMENTS.md}.
     */
    @GetMapping("/map")
    @PreAuthorize("hasAuthority('ROLE_management-node:product_discovery')")
    @Operation(
            summary = "Read the network map",
            description = "Returns every organisation that owns a producer or a consumer, those producers and "
                    + "consumers, every product with the producer that publishes it, and every subscription "
                    + "joining a consumer to a product. Enough to construct the network as a graph in one "
                    + "request. Unlike the product endpoints this is not policy-enforced: it returns the whole "
                    + "topology to any caller holding the role, and exposes relationships rather than product "
                    + "content.",
            security = {@SecurityRequirement(name = "bearerAuth")})
    @ApiResponse(
            responseCode = "200",
            description = "The network map, with empty lists where nothing exists rather than nulls",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = NetworkMapDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden: the caller does not hold product_discovery")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    public NetworkMapDTO getNetworkMap() {
        final NetworkMapDTO map = networkMapService.networkMap();
        log.debug("Network map returned: {}", map.totals());
        return map;
    }
}
