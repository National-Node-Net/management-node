/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateSigningException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.SignCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateEventType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.organisation.OrganisationRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.CertificateEventService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationCertificateService;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.PemUtil;

/**
 * Implementation of {@link CertificateSigningProvider} that delegates to
 * {@link VaultPkiService} for signing and {@link CertificateEventService} for audit.
 */
@Service
@Slf4j
public class CertificateSigningProviderImpl implements CertificateSigningProvider {

    private final VaultPkiService vaultPkiService;
    private final OrganisationCertificateService certificateService;
    private final CertificateEventService eventService;
    private final OrganisationRepository organisationRepository;
    private final String bootstrapTtl;
    private final String bootstrapOid;

    public CertificateSigningProviderImpl(
            VaultPkiService vaultPkiService,
            OrganisationCertificateService certificateService,
            CertificateEventService eventService,
            OrganisationRepository organisationRepository,
            @Value("${application.bootstrap.ttl:2h}") String bootstrapTtl,
            @Value("${application.bootstrap.oid:1.3.6.1.4.1.32473.1.1}") String bootstrapOid) {
        this.vaultPkiService = vaultPkiService;
        this.certificateService = certificateService;
        this.eventService = eventService;
        this.organisationRepository = organisationRepository;
        this.bootstrapTtl = bootstrapTtl;
        this.bootstrapOid = bootstrapOid;
    }

    @Override
    @Transactional
    public SignCertResponseDTO signAndRecord(String csrPem, String clientId) {
        OrganisationCertificateDTO cert = lookupCertificate(clientId);

        if (!Boolean.TRUE.equals(cert.getCertificateAutomationEnabled())) {
            log.warn("Certificate automation not enabled for client {}", clientId);
            throw new CertificateSigningException("Certificate automation is not enabled for this organisation");
        }

        if (!Boolean.TRUE.equals(cert.getIsRenewable())) {
            log.warn("Certificate is not marked as renewable for client {}", clientId);
            throw new CertificateSigningException("Certificate is not renewable");
        }

        cert.setRequestedAt(Timestamp.from(Instant.now()));

        SignCertResponseDTO response = vaultPkiService.signCsr(csrPem, Optional.empty(), Optional.empty());

        updateCertificateRecord(cert, response, CertificateType.AUTOMATED);
        certificateService.save(cert);
        eventService.recordEvent(cert.getId(), CertificateType.AUTOMATED, CertificateEventType.RENEWED, clientId);

        log.info("Certificate signed and recorded for client {}, serial {}", clientId, response.getSerialNumber());

        return response;
    }

    @Override
    @Transactional
    public byte[] issueBootstrapPackage(Long organisationId, String csrPem, String performedBy) {
        OrganisationCertificateDTO cert = certificateService
                .findByOrganisationId(organisationId)
                .orElseGet(() -> createCertificateRecord(organisationId));

        if (cert.getType() == CertificateType.AUTOMATED) {
            log.warn("Overwriting active automated certificate for organisation {}", organisationId);
        }

        String otherSans = bootstrapOid + ";UTF8:bootstrap";
        SignCertResponseDTO signResponse =
                vaultPkiService.signCsr(csrPem, Optional.empty(), Optional.of(bootstrapTtl), Optional.of(otherSans));

        byte[] zipBytes = assembleBootstrapBundle(signResponse);

        cert.setRequestedAt(Timestamp.from(Instant.now()));
        updateCertificateRecord(cert, signResponse, CertificateType.BOOTSTRAP);
        cert.setIsRenewable(true);
        OrganisationCertificateDTO saved = certificateService.save(cert);
        eventService.recordEvent(saved.getId(), CertificateType.BOOTSTRAP, CertificateEventType.ISSUED, performedBy);

        log.info(
                "Bootstrap certificate issued for organisation {}, serial {}",
                organisationId,
                signResponse.getSerialNumber());

        return zipBytes;
    }

    private OrganisationCertificateDTO createCertificateRecord(Long organisationId) {
        if (!organisationRepository.existsById(organisationId)) {
            throw new CertificateSigningException("Organisation not found: " + organisationId);
        }
        log.info("No certificate record found for organisation {}. Creating one.", organisationId);
        OrganisationCertificateDTO newCert = OrganisationCertificateDTO.builder()
                .organisationId(organisationId)
                .type(CertificateType.MANUAL)
                .isRenewable(false)
                .build();
        return certificateService.save(newCert);
    }

    private OrganisationCertificateDTO lookupCertificate(String clientId) {
        return certificateService.findByClientId(clientId).orElseThrow(() -> {
            log.warn("No certificate record found for client {}", clientId);
            return new CertificateSigningException("No certificate record found for client");
        });
    }

    private void updateCertificateRecord(
            OrganisationCertificateDTO cert, SignCertResponseDTO response, CertificateType type) {
        X509Certificate x509 = PemUtil.parseCertificate(response.getCertificate());
        cert.setSubjectDn(x509.getSubjectX500Principal().getName());
        cert.setSerialNumber(x509.getSerialNumber().toString(16));
        cert.setIssuedAt(Timestamp.from(x509.getNotBefore().toInstant()));
        cert.setExpiresAt(
                Timestamp.from(Instant.ofEpochSecond(response.getExpiration().longValue())));
        cert.setType(type);
    }

    private static byte[] assembleBootstrapBundle(SignCertResponseDTO signResponse) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(baos)) {
            addZipEntry(zos, "certificate.pem", signResponse.getCertificate().getBytes(StandardCharsets.UTF_8));
            String caChainPem = signResponse.getCaChain() != null ? String.join("", signResponse.getCaChain()) : "";
            addZipEntry(zos, "ca-chain.pem", caChainPem.getBytes(StandardCharsets.UTF_8));
            zos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new CertificateSigningException("Failed to assemble bootstrap certificate package", e);
        }
    }

    private static void addZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }
}
