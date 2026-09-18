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
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AbstractPostgresRepositoryTest;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.policy.PolicyAttributeScopeRepository;

/**
 * Verifies every {@link PolicyAttributeScopeCode} constant's code names a real, seeded {@code
 * policy_attribute_scope.code} row - against real Postgres, so a future rename of a seeded code would
 * be caught here rather than only surfacing as an always-empty result at runtime.
 */
class PolicyAttributeScopeCodeTest extends AbstractPostgresRepositoryTest {

    @Autowired
    private PolicyAttributeScopeRepository policyAttributeScopeRepository;

    @ParameterizedTest
    @EnumSource(PolicyAttributeScopeCode.class)
    void code_namesASeededAttributeScopeRow(PolicyAttributeScopeCode scope) {
        assertThat(policyAttributeScopeRepository.findByCode(scope.code())).isPresent();
    }

    @Test
    void coversEverySeededScopeCode() {
        assertThat(PolicyAttributeScopeCode.values())
                .extracting(PolicyAttributeScopeCode::code)
                .containsExactlyInAnyOrder("PRODUCER", "PRODUCT", "CONSUMER", "ORGANISATION", "SUBSCRIPTION");
        assertThat(policyAttributeScopeRepository.findAll())
                .extracting(PolicyAttributeScope::getCode)
                .containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(PolicyAttributeScopeCode.values())
                        .map(PolicyAttributeScopeCode::code)
                        .toList());
    }
}
