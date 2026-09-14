/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.List;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProductDTO;

/**
 * Service interface for managing OrganisationDataProvider entities.
 */
public interface ProductService {

    /**
     * Retrieves a list of OrganisationDataProviderDTO objects by their IDs.
     *
     * @param ids the list of IDs to search for
     * @return a list of OrganisationDataProviderDTO objects
     */
    List<ProductDTO> getProductsByIds(List<Long> ids);

    /**
     * Retrieves a list of DataProviderDTO objects associated with the specified producer IDs.
     *
     * @param producerIds the list of producer IDs for which data providers need to be retrieved
     * @return a list of DataProviderDTO objects corresponding to the given producer IDs
     */
    List<ProductDTO> getProductsByProducerIds(List<Long> producerIds);

    /**
     * Retrieves discovery candidate products across all organisations matching the optional
     * search filters, bounded by the configured max-candidate limit. This is the pre-policy
     * candidate set for {@code POST /v1/product/discovery}; authorisation is applied
     * separately, per candidate, by the PDP.
     *
     * @param name optional case-insensitive contains filter on product name
     * @param topic optional case-insensitive contains filter on product topic
     * @param type optional case-insensitive exact filter on product type name
     * @return candidate products matching the filters, bounded by the max-candidate limit
     */
    List<ProductDTO> findDiscoveryCandidates(String name, String topic, String type);
}
