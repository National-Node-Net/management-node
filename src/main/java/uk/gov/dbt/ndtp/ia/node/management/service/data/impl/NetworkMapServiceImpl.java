/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.network.NetworkMapDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.NetworkMapRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.NetworkMapService;

/**
 * Assembles the network map from five reads.
 *
 * There is almost no logic here, and that is deliberate: the repository returns the DTO's own
 * record types directly, so this service composes rather than converts. A converter layer
 * between two structurally identical shapes would be a place for them to drift apart.
 *
 * One transaction around all five reads, which is the only substantive decision in this class.
 * Read separately, a subscription written between the third and fifth query could reference a
 * consumer the map does not contain, and the caller would be handed an edge with a missing
 * endpoint. Reading them together means the map is a consistent snapshot: every foreign key in
 * it resolves.
 */
@Service
@Slf4j
public class NetworkMapServiceImpl implements NetworkMapService {

    private final NetworkMapRepository repository;

    public NetworkMapServiceImpl(NetworkMapRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public NetworkMapDTO networkMap() {
        final List<NetworkMapDTO.Organisation> organisations = repository.findOrganisations();
        final List<NetworkMapDTO.Producer> producers = repository.findProducers();
        final List<NetworkMapDTO.Consumer> consumers = repository.findConsumers();
        final List<NetworkMapDTO.Product> products = repository.findProducts();
        final List<NetworkMapDTO.Subscription> subscriptions = repository.findSubscriptions();

        log.debug(
                "Network map read: {} organisations, {} producers, {} consumers, {} products, {} subscriptions",
                organisations.size(),
                producers.size(),
                consumers.size(),
                products.size(),
                subscriptions.size());

        return NetworkMapDTO.builder()
                .organisations(organisations)
                .producers(producers)
                .consumers(consumers)
                .products(products)
                .subscriptions(subscriptions)
                .totals(NetworkMapDTO.Totals.builder()
                        .organisations(organisations.size())
                        .producers(producers.size())
                        .consumers(consumers.size())
                        .products(products.size())
                        .subscriptions(subscriptions.size())
                        .build())
                .build();
    }
}
