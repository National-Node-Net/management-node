/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uk.gov.dbt.ndtp.ia.node.management.config.OcspProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.IntermediateCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.SignCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.VaultPkiService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OcspSignerServiceImplTest {

    private static final String COMMON_NAME = "ocsp-signer.management-node";
    private static final Duration LIFETIME = Duration.ofHours(1);

    @Mock
    private VaultPkiService pkiService;

    private SimpleMeterRegistry meterRegistry;
    private MutableClock clock;
    private OcspSignerServiceImpl service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        clock = new MutableClock(NOW);
        OcspProperties properties = new OcspProperties(
                Duration.ofMinutes(5),
                10,
                16384,
                "vault",
                new OcspProperties.Signer(COMMON_NAME, Duration.ofHours(24), Duration.ofMinutes(5)));
        when(pkiService.getIntermediateCertificate())
                .thenReturn(IntermediateCertResponseDTO.builder()
                        .certificate(toPem(CA))
                        .build());
        vaultIssues(csr -> certificateFromCsr(
                csr, CA, CA_KEYS, clock.instant(), clock.instant().plus(LIFETIME)));
        service = new OcspSignerServiceImpl(pkiService, properties, meterRegistry, clock);
    }

    /** Makes the mocked Vault answer every CSR with the certificate built by {@code issuer}. */
    private void vaultIssues(Function<String, X509CertificateHolder> issuer) {
        doAnswer(invocation -> new SignCertResponseDTO(
                        toPem(issuer.apply(invocation.getArgument(0))), List.of(), toPem(CA), "20:00", 0))
                .when(pkiService)
                .signCsr(anyString(), any(), any());
    }

    @Test
    void refresh_shouldIssueEcSignerWithTheDefaultRole() throws Exception {
        service.refresh();

        ArgumentCaptor<String> csr = ArgumentCaptor.forClass(String.class);
        verify(pkiService).signCsr(csr.capture(), eq(Optional.empty()), eq(Optional.of("86400s")));
        assertTrue(csr.getValue().contains("BEGIN CERTIFICATE REQUEST"));

        OcspSigner signer = service.currentSigner().orElseThrow();
        assertEquals(new X500Name("CN=" + COMMON_NAME), signer.certificate().getSubject());
        assertEquals(CA, signer.issuer());
        assertEquals("SHA256withECDSA", signer.signatureAlgorithm());
        assertEquals("EC", signer.privateKey().getAlgorithm());

        byte[] data = "check".getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance(signer.signatureAlgorithm());
        signature.initSign(signer.privateKey());
        signature.update(data);
        byte[] signed = signature.sign();
        signature.initVerify(toX509(signer.certificate()).getPublicKey());
        signature.update(data);
        assertTrue(signature.verify(signed), "the private key must match the issued certificate");
    }

    @Test
    void onApplicationReady_shouldIssueSigner() {
        service.onApplicationReady();

        assertTrue(service.currentSigner().isPresent());
    }

    @Test
    void refresh_vaultFails_shouldLeaveNoSigner() {
        doThrow(new PkiException("Vault sealed")).when(pkiService).signCsr(anyString(), any(), any());

        service.refresh();

        assertTrue(service.currentSigner().isEmpty());
    }

    @Test
    void issue_notIssuedByCa_shouldThrow() {
        vaultIssues(csr -> certificateFromCsr(csr, OTHER_CA, OTHER_CA_KEYS, NOW, NOW.plus(LIFETIME)));

        PkiException e = assertThrows(PkiException.class, () -> service.issue());
        assertTrue(e.getMessage().contains("not issued by the CA"));
    }

    @Test
    void issue_issuerNameMatchesButSignatureDoesNot_shouldThrow() {
        vaultIssues(csr -> certificateFromCsr(csr, CA, OTHER_CA_KEYS, NOW, NOW.plus(LIFETIME)));

        assertThrows(PkiException.class, () -> service.issue());
    }

    @Test
    void issue_expiredCertificate_shouldThrow() {
        vaultIssues(csr -> certificateFromCsr(csr, CA, CA_KEYS, NOW.minusSeconds(7200), NOW.minusSeconds(60)));

        assertThrows(PkiException.class, () -> service.issue());
    }

    @Test
    void issue_certificateForAnotherKey_shouldThrow() {
        vaultIssues(csr -> signerCertificate(SIGNER_RSA_KEYS));

        PkiException e = assertThrows(PkiException.class, () -> service.issue());
        assertTrue(e.getMessage().contains("generated key"));
    }

    @Test
    void refresh_signerNotDueForRenewal_shouldKeepIt() {
        service.refresh();
        OcspSigner first = service.currentSigner().orElseThrow();

        clock.set(NOW.plus(Duration.ofMinutes(41)));
        service.refresh();

        verify(pkiService, times(1)).signCsr(anyString(), any(), any());
        assertSame(first, service.currentSigner().orElseThrow());
    }

    @Test
    void refresh_signerPastRenewalThreshold_shouldIssueNewSigner() {
        service.refresh();
        OcspSigner first = service.currentSigner().orElseThrow();

        // 70% of the one-hour lifetime has elapsed after 42 minutes
        clock.set(NOW.plus(Duration.ofMinutes(43)));
        service.refresh();

        verify(pkiService, times(2)).signCsr(anyString(), any(), any());
        OcspSigner renewed = service.currentSigner().orElseThrow();
        assertNotEquals(first.privateKey(), renewed.privateKey());
        assertEquals(
                NOW.plus(Duration.ofMinutes(43)),
                renewed.certificate().getNotBefore().toInstant());
    }

    @Test
    void refresh_renewalFails_shouldKeepPreviousValidSigner() {
        service.refresh();
        OcspSigner first = service.currentSigner().orElseThrow();

        clock.set(NOW.plus(Duration.ofMinutes(43)));
        doThrow(new PkiException("Vault sealed")).when(pkiService).signCsr(anyString(), any(), any());
        service.refresh();

        assertSame(first, service.currentSigner().orElseThrow());
    }

    @Test
    void currentSigner_afterCertificateExpired_shouldBeEmpty() {
        service.refresh();

        clock.set(NOW.plus(Duration.ofHours(2)));

        assertTrue(service.currentSigner().isEmpty());
    }

    @Test
    void expiryGauge_shouldReportSecondsUntilExpiry() {
        assertEquals(
                -1.0, meterRegistry.get("ocsp.signer.expiry.seconds").gauge().value());

        service.refresh();

        assertEquals(
                3600.0, meterRegistry.get("ocsp.signer.expiry.seconds").gauge().value());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
