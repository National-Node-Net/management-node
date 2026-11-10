/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

import java.time.Duration;
import lombok.Getter;

/**
 * A revocation request that cannot be carried out for a reason the caller can act on. The status follows the
 * reason, as for {@link SubscriptionRejectedException}. A certificate of another organisation is a different
 * answer: that is an {@link AccessRejectedException} and a {@code 403}.
 */
@Getter
public class CertificateRevocationRejectedException extends RuntimeException {

    /** What went wrong, so the handler can map it to a status without matching on the message. */
    public enum Reason {
        /** No organisation certificate holds the serial number: {@code 404}. */
        CERTIFICATE_NOT_FOUND,
        /** The certificate has expired, so Vault recorded no revocation: {@code 409}. */
        CERTIFICATE_EXPIRED,
        /** The caller has sent too many revocation requests: {@code 429}. */
        RATE_LIMITED
    }

    private final transient Reason reason;

    /** How long the caller should wait before trying again; null unless the reason is {@link Reason#RATE_LIMITED}. */
    private final transient Duration retryAfter;

    public CertificateRevocationRejectedException(Reason reason, String message) {
        this(reason, message, null);
    }

    public CertificateRevocationRejectedException(Reason reason, String message, Duration retryAfter) {
        super(message);
        this.reason = reason;
        this.retryAfter = retryAfter;
    }
}
