/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.converter.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.organisation.OrganisationDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;

class OrganisationConverterTest {

    private final OrganisationConverter converter = new OrganisationConverter();

    private static Organisation entity(String name, String key) {
        Organisation organisation = new Organisation();
        organisation.setName(name);
        organisation.setOrganisationKey(key);
        return organisation;
    }

    @Test
    void toDto_mapsNameAndKey() {
        OrganisationDTO dto = converter.toDto(entity("Environment Agency (ENV)", "ENV"));

        assertThat(dto.getName()).isEqualTo("Environment Agency (ENV)");
        assertThat(dto.getKey()).isEqualTo("ENV");
    }

    @Test
    void toDto_leavesPolicyAttributesEmptyForTheCallerToPopulate() {
        assertThat(converter.toDto(entity("Homes England (HEG)", "HEG")).getPolicyAttributes())
                .isEmpty();
    }

    @Test
    void toDto_returnsNullForNullEntity() {
        assertThat(converter.toDto(null)).isNull();
    }

    @Test
    void toEntity_mapsNameAndKey() {
        Organisation organisation = converter.toEntity(OrganisationDTO.builder()
                .name("Bristol City Council (BCC)")
                .key("BCC")
                .build());

        assertThat(organisation.getName()).isEqualTo("Bristol City Council (BCC)");
        assertThat(organisation.getOrganisationKey()).isEqualTo("BCC");
    }

    @Test
    void toEntity_returnsNullForNullDto() {
        assertThat(converter.toEntity(null)).isNull();
    }

    @Test
    void toDtoList_mapsEveryEntity() {
        List<OrganisationDTO> dtos = converter.toDtoList(
                List.of(entity("Environment Agency (ENV)", "ENV"), entity("Homes England (HEG)", "HEG")));

        assertThat(dtos).extracting(OrganisationDTO::getKey).containsExactly("ENV", "HEG");
    }
}
