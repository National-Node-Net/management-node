/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.utils.http;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.CacheControl;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OcspResponseDTO;

/**
 * Utility class for building {@code Cache-Control} headers.
 */
public class CacheControlUtil {

    private CacheControlUtil() {}

    /**
     * Builds the {@code Cache-Control} header for an OCSP response (RFC 5019 section 6.2).
     * Successful responses may be cached until their {@code nextUpdate}; error responses must not be cached.
     *
     * @param response the OCSP response
     * @return {@code max-age=<seconds until nextUpdate>, public, no-transform, must-revalidate} for successful
     *     responses, {@code no-store} otherwise
     */
    public static CacheControl forOcspResponse(OcspResponseDTO response) {
        return forOcspResponse(response, Instant.now());
    }

    /**
     * Builds the {@code Cache-Control} header for an OCSP response at the given time.
     *
     * @param response the OCSP response
     * @param now      the current time
     * @return the {@code Cache-Control} header
     * @see #forOcspResponse(OcspResponseDTO)
     */
    public static CacheControl forOcspResponse(OcspResponseDTO response, Instant now) {
        if (!response.isSuccessful() || response.getNextUpdate() == null) {
            return CacheControl.noStore();
        }
        long maxAge =
                Math.max(0, Duration.between(now, response.getNextUpdate()).toSeconds());
        return CacheControl.maxAge(Duration.ofSeconds(maxAge))
                .cachePublic()
                .noTransform()
                .mustRevalidate();
    }
}
