/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration;

import java.util.List;
import lombok.Builder;
import lombok.Getter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.organisation.OrganisationDTO;

@Builder
@Getter
public class ProducerConfigDTO {
    private String clientId;

    /**
     * The organisation the requesting client's producers belong to, including its key and
     * {@code ORGANISATION}-scope policy attributes. Null when no producer resolved an
     * organisation; where producers somehow span more than one, the first is used.
     */
    private OrganisationDTO organisation;

    private List<ProducerDTO> producers;
}
