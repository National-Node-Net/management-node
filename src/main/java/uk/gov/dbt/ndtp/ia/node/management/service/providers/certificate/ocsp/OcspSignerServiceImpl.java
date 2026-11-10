/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.config.OcspProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiException;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.VaultPkiService;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.PemUtil;

/**
 * Implementation of {@link OcspSignerService} that issues its own OCSP signer through the default Vault PKI role.
 *
 * <p>The signer key is an EC P-256 key generated in memory; it is never written anywhere. Its certificate is
 * issued by signing a CSR with {@link VaultPkiService#signCsr} using {@code application.vault.default-role}.
 * The signer is issued when the application is ready, and every {@code application.ocsp.signer.refresh-interval}
 * the service checks whether a new one is needed: when none is loaded, or once the current certificate has passed
 * 70% of its lifetime. If issuing fails, the previous signer is kept while it is still valid. Without a valid
 * signer the responder answers {@code tryLater}.
 */
@Service
@Slf4j
public class OcspSignerServiceImpl implements OcspSignerService {

    static final String KEY_ALGORITHM = "EC";
    static final String CURVE = "secp256r1";
    static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    static final double RENEWAL_THRESHOLD = 0.7;

    private final VaultPkiService pkiService;
    private final OcspProperties.Signer properties;
    private final Clock clock;
    private final AtomicReference<OcspSigner> current = new AtomicReference<>();

    @Autowired
    public OcspSignerServiceImpl(VaultPkiService pkiService, OcspProperties properties, MeterRegistry meterRegistry) {
        this(pkiService, properties, meterRegistry, Clock.systemUTC());
    }

    OcspSignerServiceImpl(
            VaultPkiService pkiService, OcspProperties properties, MeterRegistry meterRegistry, Clock clock) {
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
     * Issues a new signer when none is loaded or the current one is due for renewal. Failures are logged and leave
     * the previous signer in place.
     */
    @Scheduled(
            initialDelayString = "${application.ocsp.signer.refresh-interval:PT5M}",
            fixedDelayString = "${application.ocsp.signer.refresh-interval:PT5M}")
    @Override
    public void refresh() {
        OcspSigner existing = current.get();
        if (existing != null && !isDueForRenewal(existing)) {
            return;
        }
        try {
            OcspSigner signer = issue();
            current.set(signer);
            log.info(
                    "Issued OCSP signer {} (serial {}), valid until {}",
                    signer.certificate().getSubject(),
                    signer.certificate().getSerialNumber().toString(16),
                    signer.certificate().getNotAfter().toInstant());
        } catch (Exception e) {
            Optional<OcspSigner> previous = currentSigner();
            if (previous.isPresent()) {
                log.error(
                        "Failed to renew OCSP signer; keeping previous signer valid until {}",
                        previous.get().certificate().getNotAfter().toInstant(),
                        e);
            } else {
                log.error("Failed to issue OCSP signer; OCSP requests will be answered with tryLater", e);
            }
        }
    }

    /**
     * Generates a signer key, has Vault issue its certificate with the default role and validates the result.
     *
     * @return the validated signer
     * @throws PkiException if the certificate cannot be issued or is not valid
     */
    OcspSigner issue() {
        KeyPair keys = generateKeyPair();
        String certificatePem = pkiService
                .signCsr(
                        csr(keys),
                        Optional.empty(),
                        Optional.of(properties.ttl().toSeconds() + "s"))
                .getCertificate();

        X509Certificate certificate = PemUtil.parseCertificate(certificatePem);
        X509Certificate ca =
                PemUtil.parseCertificate(pkiService.getIntermediateCertificate().getCertificate());
        validate(certificate, keys.getPublic(), ca);
        try {
            return new OcspSigner(
                    keys.getPrivate(),
                    new JcaX509CertificateHolder(certificate),
                    new JcaX509CertificateHolder(ca),
                    SIGNATURE_ALGORITHM);
        } catch (Exception e) {
            throw new PkiException("Failed to encode OCSP signer certificates", e);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            generator.initialize(new ECGenParameterSpec(CURVE));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new PkiException("Failed to generate OCSP signer key", e);
        }
    }

    private String csr(KeyPair keys) {
        try {
            var csr = new JcaPKCS10CertificationRequestBuilder(
                            new X500Name("CN=" + properties.commonName()), keys.getPublic())
                    .build(new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keys.getPrivate()));
            return PemUtil.toPem("CERTIFICATE REQUEST", csr.getEncoded());
        } catch (Exception e) {
            throw new PkiException("Failed to build OCSP signer CSR", e);
        }
    }

    private void validate(X509Certificate certificate, PublicKey publicKey, X509Certificate ca) {
        try {
            if (!certificate.getPublicKey().equals(publicKey)) {
                throw new PkiException("OCSP signer certificate does not carry the generated key");
            }
            if (!certificate.getIssuerX500Principal().equals(ca.getSubjectX500Principal())) {
                throw new PkiException("OCSP signer certificate was not issued by the CA");
            }
            certificate.verify(ca.getPublicKey());
            certificate.checkValidity(Date.from(clock.instant()));
        } catch (PkiException e) {
            throw e;
        } catch (Exception e) {
            throw new PkiException("OCSP signer certificate is not valid: " + e.getMessage(), e);
        }
    }

    private boolean isDueForRenewal(OcspSigner signer) {
        Instant notBefore = signer.certificate().getNotBefore().toInstant();
        Instant notAfter = signer.certificate().getNotAfter().toInstant();
        Duration lifetime = Duration.between(notBefore, notAfter);
        Duration elapsed = Duration.between(notBefore, clock.instant());
        return elapsed.toMillis() >= lifetime.toMillis() * RENEWAL_THRESHOLD;
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
}
