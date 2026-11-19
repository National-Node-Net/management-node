/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.utils.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OcspResponseDTO;

class CacheControlUtilTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    private static OcspResponseDTO response(int responseStatus, Instant nextUpdate) {
        return OcspResponseDTO.builder()
                .encoded(new byte[] {0x30})
                .responseStatus(responseStatus)
                .nextUpdate(nextUpdate)
                .build();
    }

    @Test
    void forOcspResponse_successful_shouldAllowCachingUntilNextUpdate() {
        String header = CacheControlUtil.forOcspResponse(
                        response(OCSPRespBuilder.SUCCESSFUL, NOW.plus(Duration.ofMinutes(5))), NOW)
                .getHeaderValue();

        assertEquals("max-age=300, must-revalidate, no-transform, public", header);
    }

    @Test
    void forOcspResponse_successfulPastNextUpdate_shouldUseZeroMaxAge() {
        String header = CacheControlUtil.forOcspResponse(response(OCSPRespBuilder.SUCCESSFUL, NOW.minusSeconds(5)), NOW)
                .getHeaderValue();

        assertEquals("max-age=0, must-revalidate, no-transform, public", header);
    }

    @Test
    void forOcspResponse_successfulWithoutNextUpdate_shouldNotBeStored() {
        assertEquals(
                "no-store",
                CacheControlUtil.forOcspResponse(response(OCSPRespBuilder.SUCCESSFUL, null), NOW)
                        .getHeaderValue());
    }

    @Test
    void forOcspResponse_errorStatus_shouldNotBeStored() {
        assertEquals(
                "no-store",
                CacheControlUtil.forOcspResponse(response(OCSPRespBuilder.TRY_LATER, null), NOW)
                        .getHeaderValue());
    }
}
