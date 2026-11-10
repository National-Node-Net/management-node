/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.config.RevocationProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateRevocationRejectedException;

/**
 * Limits how many revocation requests one administrator may send per window
 * ({@code application.revocation.rate-limit}), so a compromised administrator account cannot revoke an
 * organisation's certificates in bulk.
 *
 * <p>A fixed window per caller, held in memory: each Management Node instance counts on its own, so with several
 * instances behind a load balancer a caller can send up to that many times the limit.
 */
@Component
@Slf4j
public class RevocationRateLimiter {

    private final int maxRequests;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public RevocationRateLimiter(RevocationProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RevocationRateLimiter(RevocationProperties properties, Clock clock) {
        this.maxRequests = properties.rateLimit().maxRequests();
        this.window = properties.rateLimit().window();
        this.clock = clock;
    }

    /**
     * Counts one request for the caller.
     *
     * @param caller the caller's identity, e.g. the token's subject
     * @throws CertificateRevocationRejectedException with reason {@code RATE_LIMITED} once the caller has sent
     *     more requests than the window allows
     */
    public void acquire(String caller) {
        Instant now = clock.instant();
        windows.values().removeIf(w -> w.hasEnded(now));
        Window current = windows.compute(
                caller, (key, w) -> w == null || w.hasEnded(now) ? new Window(now.plus(window), 1) : w.next());
        if (current.count() > maxRequests) {
            log.warn("Revocation rate limit of {} per {} exceeded by {}", maxRequests, window, caller);
            throw new CertificateRevocationRejectedException(
                    CertificateRevocationRejectedException.Reason.RATE_LIMITED,
                    "Too many revocation requests; try again later",
                    Duration.between(now, current.end()));
        }
    }

    private record Window(Instant end, int count) {

        boolean hasEnded(Instant now) {
            return !now.isBefore(end);
        }

        Window next() {
            return new Window(end, count + 1);
        }
    }
}
