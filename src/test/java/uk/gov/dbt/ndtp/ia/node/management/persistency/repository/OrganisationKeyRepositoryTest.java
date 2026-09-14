/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Organisation;

/**
 * Covers {@code organisation.organisation_key} against real Postgres: the finders, and the
 * NOT NULL and unique constraints the migration puts on the column. H2 would not prove the
 * constraint behaviour the migration actually creates, hence the Postgres-backed base class.
 */
class OrganisationKeyRepositoryTest extends AbstractPostgresRepositoryTest {

    @Autowired
    private OrganisationRepository organisationRepository;

    private Organisation persist(String name, String key) {
        Organisation organisation = new Organisation();
        organisation.setName(name);
        organisation.setOrganisationKey(key);
        return organisationRepository.saveAndFlush(organisation);
    }

    @Test
    void findByOrganisationKey_returnsTheMatchingOrganisation() {
        Organisation saved = persist("Environment Agency (ENV)", "KEY_FIND_ENV");

        assertThat(organisationRepository.findByOrganisationKey("KEY_FIND_ENV"))
                .isPresent()
                .get()
                .satisfies(found -> {
                    assertThat(found.getId()).isEqualTo(saved.getId());
                    assertThat(found.getName()).isEqualTo("Environment Agency (ENV)");
                    assertThat(found.getOrganisationKey()).isEqualTo("KEY_FIND_ENV");
                });
    }

    @Test
    void findByOrganisationKey_returnsEmptyForAnUnknownKey() {
        assertThat(organisationRepository.findByOrganisationKey("KEY_NOT_PRESENT"))
                .isEmpty();
    }

    @Test
    void findByOrganisationKey_isCaseSensitive() {
        persist("Homes England (HEG)", "KEY_CASE_HEG");

        assertThat(organisationRepository.findByOrganisationKey("key_case_heg")).isEmpty();
    }

    @Test
    void existsByOrganisationKey_reflectsWhetherTheKeyIsTaken() {
        persist("Bristol City Council (BCC)", "KEY_EXISTS_BCC");

        assertThat(organisationRepository.existsByOrganisationKey("KEY_EXISTS_BCC"))
                .isTrue();
        assertThat(organisationRepository.existsByOrganisationKey("KEY_EXISTS_NOBODY"))
                .isFalse();
    }

    @Test
    void organisationKey_isUnique() {
        persist("First org", "KEY_DUPLICATE");

        assertThatThrownBy(() -> persist("Second org", "KEY_DUPLICATE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void organisationKey_isMandatory() {
        Organisation noKey = new Organisation();
        noKey.setName("Org with no key");

        assertThatThrownBy(() -> organisationRepository.saveAndFlush(noKey))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
