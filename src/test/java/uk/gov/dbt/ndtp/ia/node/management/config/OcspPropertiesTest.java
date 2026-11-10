/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class OcspPropertiesTest {

    private static final Duration REFRESH = Duration.ofMinutes(5);
    private static final OcspProperties.Signer SIGNER =
            new OcspProperties.Signer("ocsp-signer.management-node", Duration.ofHours(24), REFRESH);

    private static OcspProperties withValidity(Duration validity) {
        return new OcspProperties(validity, 10, 16384, "vault", SIGNER);
    }

    @Test
    void defaults_shouldBindFromEmptyConfiguration() {
        OcspProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("application.ocsp", OcspProperties.class);

        assertEquals(Duration.ofMinutes(5), properties.validity());
        assertEquals(10, properties.maxRequestEntries());
        assertEquals(16384, properties.maxRequestBytes());
        assertEquals("vault", properties.statusBackend());
        assertEquals("ocsp-signer.management-node", properties.signer().commonName());
        assertEquals(Duration.ofHours(24), properties.signer().ttl());
        assertEquals(Duration.ofMinutes(5), properties.signer().refreshInterval());
    }

    @Test
    void validity_atMaximum_shouldBeAccepted() {
        assertEquals(
                OcspProperties.MAX_VALIDITY,
                withValidity(Duration.ofMinutes(10)).validity());
    }

    @Test
    void validity_aboveMaximum_shouldBeRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> withValidity(Duration.ofMinutes(10).plusSeconds(1)));
    }

    @Test
    void validity_zeroNegativeOrMissing_shouldBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> withValidity(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> withValidity(Duration.ofMinutes(-1)));
        assertThrows(IllegalArgumentException.class, () -> withValidity(null));
    }

    @Test
    void limits_belowOne_shouldBeRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OcspProperties(Duration.ofMinutes(5), 0, 16384, "vault", SIGNER));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OcspProperties(Duration.ofMinutes(5), 10, 0, "vault", SIGNER));
    }

    @Test
    void signerTtl_atFourRefreshIntervals_shouldBeAccepted() {
        assertEquals(
                Duration.ofMinutes(20),
                new OcspProperties.Signer("ocsp-signer", Duration.ofMinutes(20), REFRESH).ttl());
    }

    @Test
    void signerTtl_shorterThanFourRefreshIntervalsOrMissing_shouldBeRejected() {
        Duration tooShort = Duration.ofMinutes(19);
        assertThrows(IllegalArgumentException.class, () -> new OcspProperties.Signer("ocsp-signer", tooShort, REFRESH));
        assertThrows(IllegalArgumentException.class, () -> new OcspProperties.Signer("ocsp-signer", null, REFRESH));
    }

    @Test
    void signer_blankCommonNameOrNonPositiveRefresh_shouldBeRejected() {
        Duration ttl = Duration.ofHours(24);
        assertThrows(IllegalArgumentException.class, () -> new OcspProperties.Signer(" ", ttl, REFRESH));
        assertThrows(
                IllegalArgumentException.class, () -> new OcspProperties.Signer("ocsp-signer", ttl, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new OcspProperties.Signer("ocsp-signer", ttl, null));
    }
}
