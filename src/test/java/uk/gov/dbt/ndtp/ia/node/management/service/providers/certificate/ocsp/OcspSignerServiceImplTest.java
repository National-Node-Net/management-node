/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.bouncycastle.cert.X509CertificateHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.vault.VaultException;
import org.springframework.vault.core.VaultKeyValueOperations;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;
import uk.gov.dbt.ndtp.ia.node.management.config.OcspProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.IntermediateCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.VaultPkiService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OcspSignerServiceImplTest {

    private static final String KV_MOUNT = "secret";
    private static final String KV_PATH = "management-node/ocsp-signer";

    @Mock
    private VaultTemplate vaultTemplate;

    @Mock
    private VaultKeyValueOperations keyValueOperations;

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
                new OcspProperties.Signer(KV_MOUNT, KV_PATH, Duration.ofMinutes(5)));
        when(vaultTemplate.opsForKeyValue(KV_MOUNT, KeyValueBackend.KV_2)).thenReturn(keyValueOperations);
        when(pkiService.getIntermediateCertificate())
                .thenReturn(IntermediateCertResponseDTO.builder()
                        .certificate(toPem(CA))
                        .build());
        service = new OcspSignerServiceImpl(vaultTemplate, pkiService, properties, meterRegistry, clock);
    }

    private void storeSigner(KeyPair keys, X509CertificateHolder certificate) {
        VaultResponse response = new VaultResponse();
        response.setData(Map.of("private_key", privateKeyPem(keys), "certificate", toPem(certificate)));
        when(keyValueOperations.get(KV_PATH)).thenReturn(response);
    }

    @Test
    void refresh_validRsaSigner_shouldBeAvailable() {
        X509CertificateHolder certificate = signerCertificate(SIGNER_RSA_KEYS);
        storeSigner(SIGNER_RSA_KEYS, certificate);

        service.refresh();

        OcspSigner signer = service.currentSigner().orElseThrow();
        assertEquals(certificate, signer.certificate());
        assertEquals(CA, signer.issuer());
        assertEquals("SHA256withRSA", signer.signatureAlgorithm());
    }

    @Test
    void refresh_validEcSigner_shouldUseEcdsa() {
        storeSigner(SIGNER_EC_KEYS, signerCertificate(SIGNER_EC_KEYS));

        service.refresh();

        assertEquals("SHA256withECDSA", service.currentSigner().orElseThrow().signatureAlgorithm());
    }

    @Test
    void onApplicationReady_shouldLoadSigner() {
        storeSigner(SIGNER_RSA_KEYS, signerCertificate(SIGNER_RSA_KEYS));

        service.onApplicationReady();

        assertTrue(service.currentSigner().isPresent());
    }

    @Test
    void refresh_secretMissing_shouldLeaveNoSigner() {
        when(keyValueOperations.get(KV_PATH)).thenReturn(null);

        service.refresh();

        assertTrue(service.currentSigner().isEmpty());
    }

    @Test
    void load_secretMissingFields_shouldThrow() {
        VaultResponse response = new VaultResponse();
        response.setData(Map.of("certificate", toPem(signerCertificate(SIGNER_RSA_KEYS))));
        when(keyValueOperations.get(KV_PATH)).thenReturn(response);

        PkiException e = assertThrows(PkiException.class, () -> service.load());
        assertTrue(e.getMessage().contains("private_key"));
    }

    @Test
    void load_withoutOcspSigningEku_shouldThrow() {
        storeSigner(
                SIGNER_RSA_KEYS,
                signerCertificate(SIGNER_RSA_KEYS, CA, CA_KEYS, NOW.minusSeconds(60), NOW.plusSeconds(3600), false));

        PkiException e = assertThrows(PkiException.class, () -> service.load());
        assertTrue(e.getMessage().contains("id-kp-OCSPSigning"));
    }

    @Test
    void load_notIssuedByCa_shouldThrow() {
        storeSigner(
                SIGNER_RSA_KEYS,
                signerCertificate(
                        SIGNER_RSA_KEYS, OTHER_CA, OTHER_CA_KEYS, NOW.minusSeconds(60), NOW.plusSeconds(3600), true));

        assertThrows(PkiException.class, () -> service.load());
    }

    @Test
    void load_issuerNameMatchesButSignatureDoesNot_shouldThrow() {
        storeSigner(
                SIGNER_RSA_KEYS,
                signerCertificate(
                        SIGNER_RSA_KEYS, CA, OTHER_CA_KEYS, NOW.minusSeconds(60), NOW.plusSeconds(3600), true));

        assertThrows(PkiException.class, () -> service.load());
    }

    @Test
    void load_expiredCertificate_shouldThrow() {
        storeSigner(
                SIGNER_RSA_KEYS,
                signerCertificate(SIGNER_RSA_KEYS, CA, CA_KEYS, NOW.minusSeconds(7200), NOW.minusSeconds(60), true));

        assertThrows(PkiException.class, () -> service.load());
    }

    @Test
    void load_keyNotMatchingCertificate_shouldThrow() {
        VaultResponse response = new VaultResponse();
        response.setData(Map.of(
                "private_key", privateKeyPem(LEAF_KEYS),
                "certificate", toPem(signerCertificate(SIGNER_RSA_KEYS))));
        when(keyValueOperations.get(KV_PATH)).thenReturn(response);

        PkiException e = assertThrows(PkiException.class, () -> service.load());
        assertTrue(e.getMessage().contains("does not match"));
    }

    @Test
    void refresh_failure_shouldKeepPreviousValidSigner() {
        X509CertificateHolder certificate = signerCertificate(SIGNER_RSA_KEYS);
        storeSigner(SIGNER_RSA_KEYS, certificate);
        service.refresh();

        when(keyValueOperations.get(KV_PATH)).thenThrow(new VaultException("Vault sealed"));
        service.refresh();

        assertEquals(certificate, service.currentSigner().orElseThrow().certificate());
    }

    @Test
    void currentSigner_afterCertificateExpired_shouldBeEmpty() {
        storeSigner(SIGNER_RSA_KEYS, signerCertificate(SIGNER_RSA_KEYS));
        service.refresh();

        clock.set(NOW.plus(Duration.ofHours(2)));

        assertTrue(service.currentSigner().isEmpty());
    }

    @Test
    void refresh_signerPastRenewalThreshold_shouldStillLoad() {
        storeSigner(SIGNER_RSA_KEYS, signerCertificate(SIGNER_RSA_KEYS));
        // Signer is valid NOW-1h .. NOW+1h; 80% of its lifetime has elapsed at NOW+36m
        clock.set(NOW.plus(Duration.ofMinutes(36)));

        service.refresh();

        assertTrue(service.currentSigner().isPresent());
    }

    @Test
    void expiryGauge_shouldReportSecondsUntilExpiry() {
        assertEquals(
                -1.0, meterRegistry.get("ocsp.signer.expiry.seconds").gauge().value());

        storeSigner(SIGNER_RSA_KEYS, signerCertificate(SIGNER_RSA_KEYS));
        service.refresh();

        assertEquals(
                3600.0, meterRegistry.get("ocsp.signer.expiry.seconds").gauge().value());
    }

    @Test
    void signatureAlgorithmFor_unsupportedKey_shouldThrow() {
        assertThrows(PkiException.class, () -> OcspSignerServiceImpl.signatureAlgorithmFor("DSA"));
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
