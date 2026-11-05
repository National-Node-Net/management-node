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

    private static final OcspProperties.Signer SIGNER =
            new OcspProperties.Signer("secret", "management-node/ocsp-signer", Duration.ofMinutes(5));

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
        assertEquals("secret", properties.signer().kvMount());
        assertEquals("management-node/ocsp-signer", properties.signer().kvPath());
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
}
