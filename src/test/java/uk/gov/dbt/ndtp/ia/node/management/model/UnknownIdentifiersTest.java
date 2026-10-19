/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Covers {@link UnknownIdentifiers#knownOrganisationOrNull}: every "not known" form reads as null. */
class UnknownIdentifiersTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t", UnknownIdentifiers.UNKNOWN_ORG})
    void knownOrganisationOrNull_isNullForEveryUnknownForm(String organisationKey) {
        assertThat(UnknownIdentifiers.knownOrganisationOrNull(organisationKey)).isNull();
    }

    @Test
    void knownOrganisationOrNull_returnsARealKeyUnchanged() {
        assertThat(UnknownIdentifiers.knownOrganisationOrNull("ENV")).isEqualTo("ENV");
    }

    /** Only the exact sentinel is unknown; a key that merely resembles it is the caller's to own. */
    @Test
    void knownOrganisationOrNull_matchesTheSentinelExactly() {
        assertThat(UnknownIdentifiers.knownOrganisationOrNull("unknown_org")).isEqualTo("unknown_org");
    }
}
