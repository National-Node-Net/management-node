/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class ConsumerConfigDTO {

    private final String clientId;
    private final String name;
    private final String scheduleType;
    private final String scheduleExpression;
    private final List<ProducerDTO> producers;
}
