/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AbstractPostgresRepositoryTest;

class PolicyAttributeScopeRepositoryTest extends AbstractPostgresRepositoryTest {

    @Autowired
    private PolicyAttributeScopeRepository policyAttributeScopeRepository;

    @Test
    void findByCode_returnsSeededScope() {
        Optional<PolicyAttributeScope> found = policyAttributeScopeRepository.findByCode("PRODUCT");

        assertThat(found).isPresent();
        assertThat(found.get().getTableName()).isEqualTo("product");
    }

    @Test
    void findByCode_returnsEmptyForUnknownCode() {
        Optional<PolicyAttributeScope> found = policyAttributeScopeRepository.findByCode("DOES_NOT_EXIST");

        assertThat(found).isEmpty();
    }

    @Test
    void save_rejectsDuplicateCode() {
        PolicyAttributeScope duplicate = new PolicyAttributeScope();
        duplicate.setCode("PRODUCT");
        duplicate.setTableName("product");
        duplicate.setDescription("Duplicate of the seeded PRODUCT scope");

        assertThrows(
                DataIntegrityViolationException.class, () -> policyAttributeScopeRepository.saveAndFlush(duplicate));
    }
}
