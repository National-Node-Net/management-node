/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.CA;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.CA_KEYS;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.NOW;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.OTHER_CA;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.OTHER_CA_KEYS;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.caCertificate;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.leafCertificate;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.toPem;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.toX509;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.bouncycastle.cert.X509CertificateHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateStatusUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.IntermediateCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.RevocationTestProperties;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.VaultPkiService;

@ExtendWith(MockitoExtension.class)
class FederationCaServiceImplTest {

    private static final BigInteger SERIAL = BigInteger.valueOf(0x4a1f);

    @Mock
    private VaultPkiService pkiService;

    private MutableClock clock;
    private FederationCaServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        // The cache TTL of the test properties, five minutes, is how long the CA is kept.
        service = new FederationCaServiceImpl(pkiService, RevocationTestProperties.fast(), clock);
    }

    private void vaultReturns(X509CertificateHolder ca) {
        when(pkiService.getIntermediateCertificate())
                .thenReturn(IntermediateCertResponseDTO.builder()
                        .certificate(toPem(ca))
                        .build());
    }

    @Test
    void current_shouldBeTheCaOfThePkiMount() {
        vaultReturns(CA);

        assertEquals(CertificateIssuer.of(CA), service.current());
    }

    @Test
    void issuerOf_certificateIssuedByTheFederationCa_shouldBeTheFederationCa() {
        vaultReturns(CA);

        assertThat(service.issuerOf(toX509(leafCertificate(SERIAL, CA, CA_KEYS))))
                .contains(CertificateIssuer.of(CA));
    }

    @Test
    void issuerOf_certificateOfAnotherAuthority_shouldBeEmpty() {
        vaultReturns(CA);

        assertThat(service.issuerOf(toX509(leafCertificate(SERIAL, OTHER_CA, OTHER_CA_KEYS))))
                .isEmpty();
    }

    @Test
    void issuerOf_certificateNamingTheFederationCaButSignedByAnotherKey_shouldBeEmpty() {
        vaultReturns(CA);
        X509CertificateHolder impostor = caCertificate(CA.getSubject().toString(), OTHER_CA_KEYS);

        assertThat(service.issuerOf(toX509(leafCertificate(SERIAL, impostor, OTHER_CA_KEYS))))
                .isEmpty();
    }

    @Test
    void current_withinTheTtl_shouldReadVaultOnce() {
        vaultReturns(CA);

        service.current();
        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        service.current();

        verify(pkiService, times(1)).getIntermediateCertificate();
    }

    @Test
    void current_afterTheTtl_shouldReadVaultAgainAndPickUpAReplacedCa() {
        vaultReturns(CA);
        service.current();

        vaultReturns(OTHER_CA);
        clock.advance(Duration.ofMinutes(5));

        assertEquals(CertificateIssuer.of(OTHER_CA), service.current());
    }

    @Test
    void current_vaultFailsAfterTheTtl_shouldKeepTheCaAlreadyLoaded() {
        vaultReturns(CA);
        service.current();

        when(pkiService.getIntermediateCertificate()).thenThrow(new PkiException("Vault is down"));
        clock.advance(Duration.ofMinutes(6));

        assertEquals(CertificateIssuer.of(CA), service.current());
    }

    @Test
    void current_vaultFailsBeforeAnyCaWasLoaded_shouldBeUnavailable() {
        when(pkiService.getIntermediateCertificate()).thenThrow(new PkiException("Vault is down"));
        var leaf = toX509(leafCertificate(SERIAL, CA, CA_KEYS));

        assertThrows(CertificateStatusUnavailableException.class, () -> service.current());
        assertThrows(CertificateStatusUnavailableException.class, () -> service.issuerOf(leaf));
    }

    /** A clock the test moves forward by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
