/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;
import uk.gov.dbt.ndtp.ia.node.management.config.OcspProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiException;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.VaultPkiService;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.PemUtil;

/**
 * Implementation of {@link OcspSignerService} that loads the delegated OCSP signer from Vault KV v2 and the issuing
 * CA from Vault PKI.
 *
 * <p>The signer is loaded when the application is ready and reloaded every
 * {@code application.ocsp.signer.refresh-interval}, so a renewed signer written to Vault is picked up without a
 * restart. If a reload fails, the previous signer is kept while it is still valid. Without a valid signer
 * the responder answers {@code tryLater}.
 */
@Service
@Slf4j
public class OcspSignerServiceImpl implements OcspSignerService {

    static final String KEY_PRIVATE_KEY = "private_key";
    static final String KEY_CERTIFICATE = "certificate";
    private static final double RENEWAL_THRESHOLD = 0.7;
    private static final byte[] KEY_CHECK_CHALLENGE = "ocsp-signer-key-check".getBytes(StandardCharsets.UTF_8);

    private final VaultTemplate vault;
    private final VaultPkiService pkiService;
    private final OcspProperties.Signer properties;
    private final Clock clock;
    private final AtomicReference<OcspSigner> current = new AtomicReference<>();

    @Autowired
    public OcspSignerServiceImpl(
            VaultTemplate vault, VaultPkiService pkiService, OcspProperties properties, MeterRegistry meterRegistry) {
        this(vault, pkiService, properties, meterRegistry, Clock.systemUTC());
    }

    OcspSignerServiceImpl(
            VaultTemplate vault,
            VaultPkiService pkiService,
            OcspProperties properties,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.vault = vault;
        this.pkiService = pkiService;
        this.properties = properties.signer();
        this.clock = clock;
        Gauge.builder("ocsp.signer.expiry.seconds", this, OcspSignerServiceImpl::secondsUntilExpiry)
                .description("Seconds until the OCSP signer certificate expires (negative when none is loaded)")
                .register(meterRegistry);
    }

    @Override
    public Optional<OcspSigner> currentSigner() {
        Instant now = clock.instant();
        return Optional.ofNullable(current.get()).filter(signer -> signer.isValidAt(now));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    /**
     * Reloads the signer from Vault. Failures are logged and leave the previous signer in place.
     */
    @Scheduled(
            initialDelayString = "${application.ocsp.signer.refresh-interval:PT5M}",
            fixedDelayString = "${application.ocsp.signer.refresh-interval:PT5M}")
    @Override
    public void refresh() {
        try {
            OcspSigner signer = load();
            current.set(signer);
            log.info(
                    "Loaded OCSP signer {} (serial {}), valid until {}",
                    signer.certificate().getSubject(),
                    signer.certificate().getSerialNumber().toString(16),
                    signer.certificate().getNotAfter().toInstant());
            warnIfDueForRenewal(signer);
        } catch (Exception e) {
            Optional<OcspSigner> previous = currentSigner();
            if (previous.isPresent()) {
                log.error(
                        "Failed to reload OCSP signer from {}/{}; keeping previous signer valid until {}",
                        properties.kvMount(),
                        properties.kvPath(),
                        previous.get().certificate().getNotAfter().toInstant(),
                        e);
            } else {
                log.error(
                        "Failed to load OCSP signer from {}/{}; OCSP requests will be answered with tryLater",
                        properties.kvMount(),
                        properties.kvPath(),
                        e);
            }
        }
    }

    /**
     * Loads and validates the signer key and certificate from Vault.
     *
     * @return the validated signer
     * @throws PkiException if the secret is missing or the signer is not valid
     */
    OcspSigner load() {
        VaultResponse resp =
                vault.opsForKeyValue(properties.kvMount(), KeyValueBackend.KV_2).get(properties.kvPath());
        Map<String, Object> data =
                Optional.ofNullable(resp).map(VaultResponse::getData).orElse(Map.of());
        if (!(data.get(KEY_PRIVATE_KEY) instanceof String keyPem)
                || !(data.get(KEY_CERTIFICATE) instanceof String certPem)) {
            throw new PkiException("OCSP signer secret must contain '" + KEY_PRIVATE_KEY + "' and '" + KEY_CERTIFICATE
                    + "' at " + properties.kvMount() + "/" + properties.kvPath());
        }

        X509Certificate certificate = PemUtil.parseCertificate(certPem);
        String keyAlgorithm = certificate.getPublicKey().getAlgorithm();
        PrivateKey privateKey = PemUtil.parsePkcs8PrivateKey(keyPem, keyAlgorithm);
        X509Certificate ca =
                PemUtil.parseCertificate(pkiService.getIntermediateCertificate().getCertificate());
        String signatureAlgorithm = signatureAlgorithmFor(keyAlgorithm);

        validate(certificate, privateKey, ca, signatureAlgorithm);
        try {
            return new OcspSigner(
                    privateKey,
                    new JcaX509CertificateHolder(certificate),
                    new JcaX509CertificateHolder(ca),
                    signatureAlgorithm);
        } catch (Exception e) {
            throw new PkiException("Failed to encode OCSP signer certificates", e);
        }
    }

    private void validate(X509Certificate certificate, PrivateKey privateKey, X509Certificate ca, String algorithm) {
        try {
            List<String> eku = certificate.getExtendedKeyUsage();
            if (eku == null || !eku.contains(KeyPurposeId.id_kp_OCSPSigning.getId())) {
                throw new PkiException("OCSP signer certificate does not carry the id-kp-OCSPSigning EKU");
            }
            if (!certificate.getIssuerX500Principal().equals(ca.getSubjectX500Principal())) {
                throw new PkiException("OCSP signer certificate was not issued by the CA");
            }
            certificate.verify(ca.getPublicKey());
            certificate.checkValidity(Date.from(clock.instant()));

            Signature signature = Signature.getInstance(algorithm);
            signature.initSign(privateKey);
            signature.update(KEY_CHECK_CHALLENGE);
            byte[] signed = signature.sign();
            signature.initVerify(certificate.getPublicKey());
            signature.update(KEY_CHECK_CHALLENGE);
            if (!signature.verify(signed)) {
                throw new PkiException("OCSP signer private key does not match its certificate");
            }
        } catch (PkiException e) {
            throw e;
        } catch (Exception e) {
            throw new PkiException("OCSP signer certificate is not valid: " + e.getMessage(), e);
        }
    }

    private void warnIfDueForRenewal(OcspSigner signer) {
        Instant notBefore = signer.certificate().getNotBefore().toInstant();
        Instant notAfter = signer.certificate().getNotAfter().toInstant();
        Duration lifetime = Duration.between(notBefore, notAfter);
        Duration elapsed = Duration.between(notBefore, clock.instant());
        if (elapsed.toMillis() >= lifetime.toMillis() * RENEWAL_THRESHOLD) {
            log.warn(
                    "OCSP signer certificate has passed {}% of its lifetime and expires at {}; renew it",
                    Math.round(RENEWAL_THRESHOLD * 100), notAfter);
        }
    }

    private double secondsUntilExpiry() {
        OcspSigner signer = current.get();
        if (signer == null) {
            return -1;
        }
        return Duration.between(
                        clock.instant(), signer.certificate().getNotAfter().toInstant())
                .toSeconds();
    }

    static String signatureAlgorithmFor(String keyAlgorithm) {
        return switch (keyAlgorithm) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> throw new PkiException("Unsupported OCSP signer key algorithm: " + keyAlgorithm);
        };
    }
}
