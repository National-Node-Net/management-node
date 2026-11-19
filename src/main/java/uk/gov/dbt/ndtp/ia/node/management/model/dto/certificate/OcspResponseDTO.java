/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;

/**
 * DER-encoded OCSP response produced by the OCSP responder.
 */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OcspResponseDTO {

    /** The DER-encoded OCSPResponse. */
    private byte[] encoded;

    /** The OCSP response status, one of the {@link OCSPRespBuilder} constants. */
    private int responseStatus;

    /** The nextUpdate of a successful response, null otherwise. */
    private Instant nextUpdate;

    public boolean isSuccessful() {
        return responseStatus == OCSPRespBuilder.SUCCESSFUL;
    }
}
