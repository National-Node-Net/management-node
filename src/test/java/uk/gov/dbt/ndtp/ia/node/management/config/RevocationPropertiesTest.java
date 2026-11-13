/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RevocationPropertiesTest {

    private static RevocationProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("application.revocation", RevocationProperties.class);
    }

    @Test
    void defaults_shouldEnableEnforcementFailClosedWithAFiveMinuteCache() {
        RevocationProperties properties = bind(Map.of());

        assertTrue(properties.enforcement().enabled(), "the feature flag defaults to on");
        assertTrue(properties.enforcement().failClosed());
        assertEquals(
                List.of("/api/v1/certificate/ocsp", "/actuator/health"),
                properties.enforcement().excludedPaths());
        assertThat(properties.enforcement().exemptIssuers()).isEmpty();
        assertEquals(Duration.ofMinutes(5), properties.cache().ttl());
    }

    @Test
    void enforcement_shouldBindFlagPathsAndIssuers() {
        RevocationProperties properties = bind(Map.of(
                "application.revocation.enforcement.enabled", "false",
                "application.revocation.enforcement.fail-closed", "false",
                "application.revocation.enforcement.excluded-paths[0]", "/ocsp",
                "application.revocation.enforcement.exempt-issuers[0]", "CN=Dev Root CA, O=Example, C=GB"));

        assertFalse(properties.enforcement().enabled());
        assertFalse(properties.enforcement().failClosed());
        assertEquals(List.of("/ocsp"), properties.enforcement().excludedPaths());
        assertEquals(
                List.of("CN=Dev Root CA, O=Example, C=GB"),
                properties.enforcement().exemptIssuers());
    }

    @Test
    void exemptIssuer_thatIsNotADistinguishedName_shouldBeRejected() {
        List<String> paths = List.of();
        List<String> issuers = List.of("not a distinguished name");

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> new RevocationProperties.Enforcement(true, true, paths, issuers));

        assertThat(e).hasMessageContaining("exempt-issuers").hasMessageContaining("not a distinguished name");
    }

    @Test
    void cacheTtl_shouldBeConfigurable() {
        assertEquals(
                Duration.ofMinutes(2),
                bind(Map.of("application.revocation.cache.ttl", "2m")).cache().ttl());
    }

    @Test
    void cacheTtl_atMaximum_shouldBeAccepted() {
        assertEquals(RevocationProperties.Cache.MAX_TTL, new RevocationProperties.Cache(Duration.ofMinutes(10)).ttl());
    }

    @Test
    void cacheTtl_aboveMaximum_shouldFailWithAMessageNamingThePropertyAndTheLimit() {
        Map<String, String> properties = Map.of("application.revocation.cache.ttl", "11m");

        BindException e = assertThrows(BindException.class, () -> bind(properties));

        assertThat(e)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("application.revocation.cache.ttl")
                .hasMessageContaining("PT10M")
                .hasMessageContaining("PT11M");
    }

    @Test
    void cacheTtl_zeroNegativeOrMissing_shouldBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RevocationProperties.Cache(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new RevocationProperties.Cache(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new RevocationProperties.Cache(null));
    }
}
