/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateRevocationRejectedException;

class RevocationRateLimiterTest {

    private static final Instant START = Instant.parse("2026-10-12T13:00:00Z");

    private MutableClock clock;
    private RevocationRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        limiter = new RevocationRateLimiter(RevocationTestProperties.withRateLimit(3, Duration.ofMinutes(1)), clock);
    }

    @Test
    void acquire_withinTheLimit_shouldPass() {
        assertDoesNotThrow(() -> {
            limiter.acquire("admin-a");
            limiter.acquire("admin-a");
            limiter.acquire("admin-a");
        });
    }

    @Test
    void acquire_aboveTheLimit_shouldBeRateLimitedUntilTheWindowEnds() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("admin-a");
        }
        clock.advance(Duration.ofSeconds(20));

        CertificateRevocationRejectedException e =
                assertThrows(CertificateRevocationRejectedException.class, () -> limiter.acquire("admin-a"));

        assertEquals(CertificateRevocationRejectedException.Reason.RATE_LIMITED, e.getReason());
        assertEquals(Duration.ofSeconds(40), e.getRetryAfter());
    }

    @Test
    void acquire_shouldCountEachCallerSeparately() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("admin-a");
        }

        assertDoesNotThrow(() -> limiter.acquire("admin-b"));
        assertThrows(CertificateRevocationRejectedException.class, () -> limiter.acquire("admin-a"));
    }

    @Test
    void acquire_afterTheWindow_shouldStartAgain() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("admin-a");
        }
        assertThrows(CertificateRevocationRejectedException.class, () -> limiter.acquire("admin-a"));

        clock.advance(Duration.ofMinutes(1));

        assertDoesNotThrow(() -> limiter.acquire("admin-a"));
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
