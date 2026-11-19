/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateStatusUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.PemUtil;

/**
 * Certificate status backed by the Vault PKI engine ({@code GET <pki-mount>/cert/:serial}).
 * Results are never cached, so a revocation in Vault is visible on the next request.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "application.ocsp.status-backend", havingValue = "vault", matchIfMissing = true)
public class VaultCertificateStatusProvider implements CertificateStatusProvider {

    private static final String KEY_CERTIFICATE = "certificate";
    private static final String KEY_REVOCATION_TIME = "revocation_time";
    private static final HexFormat VAULT_SERIAL_FORMAT = HexFormat.ofDelimiter(":");

    private final VaultTemplate vault;
    private final String pathCert;
    private final Counter vaultErrors;

    public VaultCertificateStatusProvider(
            VaultTemplate vault,
            @Value("${application.vault.pki-mount:pki-int}") String pkiMount,
            MeterRegistry meterRegistry) {
        this.vault = vault;
        this.pathCert = pkiMount + "/cert/";
        this.vaultErrors = Counter.builder("ocsp.vault.errors")
                .description("Failed certificate status lookups against Vault")
                .register(meterRegistry);
    }

    @Override
    public CertificateStatusDTO getStatus(BigInteger serial, X509CertificateHolder issuer) {
        String vaultSerial = toVaultSerial(serial);
        VaultResponse resp;
        try {
            resp = vault.read(pathCert + vaultSerial);
        } catch (RuntimeException e) {
            vaultErrors.increment();
            log.error("Vault certificate status lookup failed for serial {}", vaultSerial, e);
            throw new CertificateStatusUnavailableException("Vault certificate status lookup failed", e);
        }

        Map<String, Object> data =
                Optional.ofNullable(resp).map(VaultResponse::getData).orElse(Map.of());
        if (!(data.get(KEY_CERTIFICATE) instanceof String certificatePem) || certificatePem.isBlank()) {
            log.debug("Serial {} not found in Vault", vaultSerial);
            return CertificateStatusDTO.unknown();
        }
        if (!isIssuedBy(certificatePem, issuer)) {
            log.warn("Serial {} found in Vault but not issued by {}", vaultSerial, issuer.getSubject());
            return CertificateStatusDTO.unknown();
        }

        long revocationTime = data.get(KEY_REVOCATION_TIME) instanceof Number n ? n.longValue() : 0L;
        if (revocationTime > 0) {
            return CertificateStatusDTO.revoked(Instant.ofEpochSecond(revocationTime));
        }
        return CertificateStatusDTO.good();
    }

    /**
     * Formats a serial number the way Vault keys certificates: lowercase hex bytes separated by colons,
     * without the leading sign byte of the two's complement encoding.
     *
     * @param serial the certificate serial number
     * @return the Vault serial, e.g. {@code 1a:2b:3c}
     */
    static String toVaultSerial(BigInteger serial) {
        byte[] bytes = serial.toByteArray();
        int from = (bytes.length > 1 && bytes[0] == 0) ? 1 : 0;
        return VAULT_SERIAL_FORMAT.formatHex(bytes, from, bytes.length);
    }

    private static boolean isIssuedBy(String certificatePem, X509CertificateHolder issuer) {
        try {
            X509CertificateHolder cert = new X509CertificateHolder(
                    PemUtil.parseCertificate(certificatePem).getEncoded());
            return cert.getIssuer().equals(issuer.getSubject())
                    && cert.isSignatureValid(new JcaContentVerifierProviderBuilder().build(issuer));
        } catch (Exception e) {
            log.warn("Unable to verify certificate returned by Vault against issuer {}", issuer.getSubject(), e);
            return false;
        }
    }
}
