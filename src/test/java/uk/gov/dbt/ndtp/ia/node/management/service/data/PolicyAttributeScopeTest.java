/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AbstractPostgresRepositoryTest;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AttributeScopeRepository;

/**
 * Verifies every {@link PolicyAttributeScope} constant's code names a real, seeded {@code
 * policy_attribute_scope.code} row - against real Postgres, so a future rename of a seeded code would
 * be caught here rather than only surfacing as an always-empty result at runtime.
 */
class PolicyAttributeScopeTest extends AbstractPostgresRepositoryTest {

    @Autowired
    private AttributeScopeRepository attributeScopeRepository;

    @ParameterizedTest
    @EnumSource(PolicyAttributeScope.class)
    void code_namesASeededAttributeScopeRow(PolicyAttributeScope scope) {
        assertThat(attributeScopeRepository.findByCode(scope.code())).isPresent();
    }

    @Test
    void coversEverySeededScopeCode() {
        assertThat(PolicyAttributeScope.values())
                .extracting(PolicyAttributeScope::code)
                .containsExactlyInAnyOrder("PRODUCER", "PRODUCT", "CONSUMER", "ORGANISATION", "SUBSCRIPTION");
        assertThat(attributeScopeRepository.findAll())
                .extracting(AttributeScope::getCode)
                .containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(PolicyAttributeScope.values())
                        .map(PolicyAttributeScope::code)
                        .toList());
    }
}
