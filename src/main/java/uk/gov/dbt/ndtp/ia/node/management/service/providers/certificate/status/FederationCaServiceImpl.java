/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.security.auth.x500.X500Principal;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.cert.X509CertificateHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.config.RevocationProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateStatusUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.VaultPkiService;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.PemUtil;

/**
 * Implementation of {@link FederationCaService}: the CA of the Vault PKI mount, kept in memory and read again once
 * it is older than the status cache TTL, so a replaced CA is picked up as fast as a changed status.
 *
 * <p>If reading it again fails, the CA already held stays in use: a CA changes far less often than Vault is
 * briefly unreachable, and statuses already cached for it must remain usable until they expire.
 */
@Service
@Slf4j
public class FederationCaServiceImpl implements FederationCaService {

    private final VaultPkiService pkiService;
    private final Duration maxAge;
    private final Clock clock;
    private final AtomicReference<Loaded> loaded = new AtomicReference<>();

    @Autowired
    public FederationCaServiceImpl(VaultPkiService pkiService, RevocationProperties properties) {
        this(pkiService, properties, Clock.systemUTC());
    }

    public FederationCaServiceImpl(VaultPkiService pkiService, RevocationProperties properties, Clock clock) {
        this.pkiService = pkiService;
        this.maxAge = properties.cache().ttl();
        this.clock = clock;
    }

    @Override
    public CertificateIssuer current() {
        return load().issuer();
    }

    @Override
    public Optional<CertificateIssuer> issuerOf(X509Certificate certificate) {
        Loaded ca = load();
        if (!ca.subject().equals(certificate.getIssuerX500Principal())) {
            return Optional.empty();
        }
        try {
            certificate.verify(ca.publicKey());
            return Optional.of(ca.issuer());
        } catch (GeneralSecurityException e) {
            log.debug("Certificate names the federation CA as issuer but was not signed by it: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Loaded load() {
        Loaded current = loaded.get();
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.loadedAt().plus(maxAge))) {
            return current;
        }
        try {
            X509Certificate certificate = PemUtil.parseCertificate(
                    pkiService.getIntermediateCertificate().getCertificate());
            Loaded fresh = new Loaded(
                    CertificateIssuer.of(new X509CertificateHolder(certificate.getEncoded())),
                    certificate.getPublicKey(),
                    certificate.getSubjectX500Principal(),
                    now);
            loaded.set(fresh);
            return fresh;
        } catch (Exception e) {
            if (current != null) {
                log.warn("Unable to read the federation CA again; keeping the one loaded at {}", current.loadedAt(), e);
                return current;
            }
            throw new CertificateStatusUnavailableException("The federation CA could not be loaded", e);
        }
    }

    private record Loaded(CertificateIssuer issuer, PublicKey publicKey, X500Principal subject, Instant loadedAt) {}
}
