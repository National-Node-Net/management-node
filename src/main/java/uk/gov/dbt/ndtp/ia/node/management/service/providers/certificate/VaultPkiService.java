/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateInfoDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CreateCsrRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CreateCsrResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CreateKeyResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.IntermediateCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.SignCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.PemUtil;

/**
 * Service for managing PKI operations using HashiCorp Vault.
 * Provides functionality for creating key pairs, CSRs, signing CSRs, and retrieving intermediate certificates.
 */
@Service
@Slf4j
public class VaultPkiService {

    private final VaultTemplate vault;
    private final String defaultRole;
    private final String defaultTtl;

    // Vault paths
    private final String pathSign;
    private final String pathCertCa;
    private final String pathCertCaChain;

    // Vault response keys
    private static final String KEY_CERTIFICATE = "certificate";
    private static final String KEY_CA_CHAIN = "ca_chain";
    private static final String KEY_ISSUING_CA = "issuing_ca";
    private static final String KEY_SERIAL_NUMBER = "serial_number";
    private static final String KEY_EXPIRATION = "expiration";

    // Request parameter keys
    private static final String PARAM_CSR = "csr";
    private static final String PARAM_TTL = "ttl";
    private static final String PARAM_FORMAT = "format";
    private static final String PARAM_OTHER_SANS = "other_sans";

    public VaultPkiService(
            VaultTemplate vault,
            @Value("${application.vault.pki-mount:pki-int}") String pkiMount,
            @Value("${application.vault.default-role:default-role}") String defaultRole,
            @Value("${application.vault.default-ttl:24h}") String defaultTtl) {
        this.vault = vault;
        this.defaultRole = defaultRole;
        this.defaultTtl = defaultTtl;
        this.pathSign = pkiMount + "/sign/";
        this.pathCertCa = pkiMount + "/cert/ca";
        this.pathCertCaChain = pkiMount + "/cert/ca_chain";
    }

    /**
     * Creates a new RSA or specified algorithm key pair and returns it as a PEM-encoded DTO.
     *
     * @param algorithm the key algorithm (defaults to RSA if null or blank)
     * @param keySize the size of the key (defaults to 2048 if null)
     * @return a DTO containing the public and private key in PEM format
     * @throws PkiException if key pair generation fails
     */
    public CreateKeyResponseDTO createKeyPair(String algorithm, Integer keySize) {
        String alg = (algorithm == null || algorithm.isBlank()) ? "RSA" : algorithm;
        int size = (keySize == null) ? 2048 : keySize;

        log.info("Creating key pair with algorithm: {} and size: {}", alg, size);
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(alg);
            if ("RSA".equalsIgnoreCase(alg)) {
                kpg.initialize(size);
            }
            KeyPair kp = kpg.generateKeyPair();

            String privateKeyPem = PemUtil.toPem("PRIVATE KEY", kp.getPrivate().getEncoded());
            String publicKeyPem = PemUtil.toPem("PUBLIC KEY", kp.getPublic().getEncoded());

            return CreateKeyResponseDTO.builder()
                    .createdAt(Instant.now().toString())
                    .algorithm(alg)
                    .publicKeyPem(publicKeyPem)
                    .privateKeyPem(privateKeyPem)
                    .build();
        } catch (NoSuchAlgorithmException e) {
            log.error("Failed to create key pair: algorithm {} not found", alg, e);
            throw new PkiException("Key pair generation failed: " + alg, e);
        }
    }

    /**
     * Creates a Certificate Signing Request (CSR) from provided public and private keys and subject information.
     *
     * @param req the CSR request DTO containing keys and subject details
     * @return a DTO containing the generated CSR PEM and a unique ID
     * @throws PkiException if CSR creation or signing fails
     */
    public CreateCsrResponseDTO createCsr(CreateCsrRequestDTO req) {
        log.info("Creating CSR for common name: {}", req.getCommonName());
        try {
            PrivateKey privateKey = PemUtil.parsePkcs8PrivateKey(req.getPrivateKeyPem());
            var publicKey = PemUtil.parsePublicKey(req.getPublicKeyPem());
            KeyPair keyPair = new KeyPair(publicKey, privateKey);

            String subject = String.format(
                    "CN=%s, OU=%s, O=%s, C=%s",
                    safe(req.getCommonName()),
                    safe(req.getOrganizationalUnit()),
                    safe(req.getOrganization()),
                    safe(req.getCountry()));

            String csrPem = buildCsr(keyPair, new X500Name(subject), req.getDnsSans());
            String csrId = UUID.randomUUID().toString();

            return new CreateCsrResponseDTO(csrId, csrPem);
        } catch (PkiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create CSR for subject common name: {}", req.getCommonName(), e);
            throw new PkiException("CSR creation failed", e);
        }
    }

    /**
     * Builds a PKCS#10 CSR from the given key pair, subject, and optional DNS SANs.
     *
     * @param keyPair the key pair to sign the CSR with
     * @param subject the X.500 distinguished name for the subject
     * @param dnsSans optional list of DNS Subject Alternative Names
     * @return the CSR in PEM format
     * @throws Exception if CSR building or signing fails
     */
    private String buildCsr(KeyPair keyPair, X500Name subject, List<String> dnsSans) throws Exception {
        JcaPKCS10CertificationRequestBuilder csrBuilder =
                new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());

        if (dnsSans != null && !dnsSans.isEmpty()) {
            log.debug("Adding DNS SANs to CSR: {}", dnsSans);
            GeneralNames sans = new GeneralNames(dnsSans.stream()
                    .map(d -> new GeneralName(GeneralName.dNSName, d))
                    .toArray(GeneralName[]::new));
            csrBuilder.addAttribute(
                    PKCSObjectIdentifiers.pkcs_9_at_extensionRequest,
                    new Extensions(new Extension(Extension.subjectAlternativeName, false, sans.getEncoded())));
        }

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        PKCS10CertificationRequest csr = csrBuilder.build(signer);
        return PemUtil.toPem("CERTIFICATE REQUEST", csr.getEncoded());
    }

    /**
     * Signs a CSR using the specified role in Vault.
     *
     * @param csrPem the CSR in PEM format
     * @param role the Vault PKI role to use for signing
     * @param ttl the requested Time To Live for the certificate
     * @return a DTO containing the signed certificate and its chain
     * @throws PkiException if signing fails or Vault returns an empty response
     */
    public SignCertResponseDTO signCsr(String csrPem, Optional<String> role, Optional<String> ttl) {
        return signCsr(csrPem, role, ttl, Optional.empty());
    }

    /**
     * Signs a CSR using the specified role in Vault, with optional custom SANs.
     *
     * @param csrPem the CSR in PEM format
     * @param role the Vault PKI role to use for signing
     * @param ttl the requested Time To Live for the certificate
     * @param otherSans optional other SANs to include (e.g. "1.3.6.1.4.1.32473.1.1;UTF8:bootstrap")
     * @return a DTO containing the signed certificate and its chain
     * @throws PkiException if signing fails or Vault returns an empty response
     */
    public SignCertResponseDTO signCsr(
            String csrPem, Optional<String> role, Optional<String> ttl, Optional<String> otherSans) {
        String effectiveRole = role.filter(StringUtils::isNotBlank).orElse(defaultRole);
        String effectiveTtl = ttl.filter(StringUtils::isNotBlank).orElse(defaultTtl);
        log.info("Signing CSR with role: {} and TTL: {}", effectiveRole, effectiveTtl);

        if (StringUtils.isEmpty(csrPem)) {
            log.warn("Sign CSR request failed: CSR PEM is empty");
            throw new PkiException("CSR PEM cannot be empty");
        }
        if (StringUtils.isEmpty(effectiveRole)) {
            log.warn("Sign CSR request failed: Role is empty");
            throw new PkiException("Role cannot be empty");
        }

        String path = pathSign + effectiveRole;

        Map<String, Object> body = new HashMap<>();
        body.put(PARAM_CSR, csrPem);
        if (effectiveTtl != null && !effectiveTtl.isBlank()) {
            body.put(PARAM_TTL, effectiveTtl);
        }
        body.put(PARAM_FORMAT, "pem");
        otherSans.filter(StringUtils::isNotBlank).ifPresent(sans -> body.put(PARAM_OTHER_SANS, sans));

        try {
            VaultResponse resp = vault.write(path, body);
            if (Optional.ofNullable(resp).map(VaultResponse::getData).isEmpty()) {
                log.error("Vault returned empty response for CSR signing at path: {}", path);
                throw new PkiException("No response from Vault PKI sign endpoint");
            }

            log.info("Successfully signed CSR with role: {}", effectiveRole);
            Object caChainObj = resp.getData().get(KEY_CA_CHAIN);
            List<String> caChain = Collections.emptyList();
            if (caChainObj instanceof List<?> list) {
                caChain = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
            }

            return new SignCertResponseDTO(
                    resp.getData().get(KEY_CERTIFICATE).toString(),
                    caChain,
                    resp.getData().get(KEY_ISSUING_CA).toString(),
                    resp.getData().get(KEY_SERIAL_NUMBER).toString(),
                    (Number) resp.getData().get(KEY_EXPIRATION));
        } catch (Exception e) {
            if (e instanceof PkiException) {
                throw e;
            }
            log.error("Error occurred while signing CSR with role: {}", effectiveRole, e);
            throw new PkiException("Failed to sign CSR via Vault", e);
        }
    }

    /**
     * Retrieves the intermediate certificate and its chain from Vault.
     * Parses the certificate to provide metadata in the response.
     *
     * @return a DTO containing the PEM certificate, CA chain, and parsed info
     * @throws PkiException if certificate retrieval or parsing fails
     */
    public IntermediateCertResponseDTO getIntermediateCertificate() {
        log.info("Retrieving intermediate certificate from Vault");
        String path = pathCertCa;
        VaultResponse resp = vault.read(path);
        if (Optional.ofNullable(resp).map(VaultResponse::getData).isEmpty()) {
            log.error("Failed to retrieve intermediate certificate from path: {}", path);
            throw new PkiException("Could not retrieve intermediate certificate from Vault");
        }

        String pathToCaChain = pathCertCaChain;
        VaultResponse caChainResp = vault.read(pathToCaChain);
        if (Optional.ofNullable(caChainResp).map(VaultResponse::getData).isEmpty()) {
            log.error("Failed to retrieve CA chain from path: {}", pathToCaChain);
            throw new PkiException("Could not retrieve CA Chain certificate from Vault");
        }

        String certificatePem = (String) resp.getData().get(KEY_CERTIFICATE);
        CertificateInfoDTO info;
        try {
            X509Certificate x509 = PemUtil.parseCertificate(certificatePem);
            info = CertificateInfoDTO.builder()
                    .subject(x509.getSubjectX500Principal().getName())
                    .issuer(x509.getIssuerX500Principal().getName())
                    .serialNumber(x509.getSerialNumber().toString())
                    .notBefore(x509.getNotBefore().toInstant())
                    .notAfter(x509.getNotAfter().toInstant())
                    .signatureAlgorithm(x509.getSigAlgName())
                    .version(x509.getVersion())
                    .build();
            log.debug("Successfully parsed intermediate certificate: {}", info.getSubject());
        } catch (Exception e) {
            log.error("Failed to parse intermediate certificate PEM", e);
            throw new PkiException("Error parsing intermediate certificate", e);
        }

        return IntermediateCertResponseDTO.builder()
                .certificate(certificatePem)
                .caChain(caChainResp.getData().get(KEY_CA_CHAIN))
                .info(info)
                .build();
    }

    /**
     * Safely formats a subject component by replacing commas with spaces.
     *
     * @param s the string to safe format
     * @return the safe string or empty string if null
     */
    private static String safe(String s) {
        return (s == null) ? "" : s.replace(",", " ");
    }
}
