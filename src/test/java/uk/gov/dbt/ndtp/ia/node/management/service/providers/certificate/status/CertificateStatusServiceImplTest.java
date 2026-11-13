/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.CA;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.NOW;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.certId;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateStatusUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.CertificateStatusProvider;

/** What the status service adds to its provider; the caching itself is in {@code AbstractCertificateStatusCacheTest}. */
@ExtendWith(MockitoExtension.class)
class CertificateStatusServiceImplTest {

    private static final BigInteger SERIAL = new BigInteger("39dd2e", 16);
    private static final CertificateIssuer ISSUER = CertificateIssuer.of(CA);

    @Mock
    private CertificateStatusProvider statusProvider;

    private SimpleMeterRegistry meterRegistry;
    private CertificateStatusServiceImpl service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new CertificateStatusServiceImpl(statusProvider, meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private double lookups(String result) {
        return meterRegistry
                .counter("revocation.status.lookups", "result", result)
                .count();
    }

    @Test
    void issuerKeyHash_shouldBeTheSha1IssuerKeyHashOfAnOcspRequest() {
        CertificateID certId = certId(CA, SERIAL);

        assertEquals(Hex.toHexString(certId.toASN1Primitive().getIssuerKeyHash().getOctets()), ISSUER.keyHash());
    }

    @Test
    void getStatus_good_shouldCarryTheTimeItWasChecked() {
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.good());

        CertificateStatusDTO status = service.getStatus(ISSUER, SERIAL);

        assertEquals(CertificateStatusDTO.Status.GOOD, status.getStatus());
        assertEquals(NOW, status.getCheckedAt());
        assertNull(status.getRevokedAt());
        assertEquals(1, lookups("good"));
    }

    @Test
    void getStatus_revoked_shouldKeepTheRevocationTimeAndReason() {
        Instant revokedAt = Instant.parse("2026-10-12T13:15:02Z");
        when(statusProvider.getStatus(SERIAL, CA))
                .thenReturn(CertificateStatusDTO.revoked(revokedAt, RevocationReason.KEY_COMPROMISE));

        CertificateStatusDTO status = service.getStatus(ISSUER, SERIAL);

        assertEquals(CertificateStatusDTO.Status.REVOKED, status.getStatus());
        assertEquals(revokedAt, status.getRevokedAt());
        assertEquals(RevocationReason.KEY_COMPROMISE, status.getRevocationReason());
        assertEquals(NOW, status.getCheckedAt());
        assertEquals(1, lookups("revoked"));
    }

    @Test
    void getStatus_unknown_shouldBeCounted() {
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.unknown());

        assertEquals(
                CertificateStatusDTO.Status.UNKNOWN,
                service.getStatus(ISSUER, SERIAL).getStatus());
        assertEquals(1, lookups("unknown"));
    }

    @Test
    void getStatus_backendUnavailable_shouldPropagateAndBeCounted() {
        when(statusProvider.getStatus(SERIAL, CA))
                .thenThrow(new CertificateStatusUnavailableException("Vault is down", null));

        assertThrows(CertificateStatusUnavailableException.class, () -> service.getStatus(ISSUER, SERIAL));
        assertEquals(1, lookups("unavailable"));
    }
}
