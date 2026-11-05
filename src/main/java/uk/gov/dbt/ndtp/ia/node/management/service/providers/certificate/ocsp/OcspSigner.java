/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import java.security.PrivateKey;
import java.time.Instant;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Delegated OCSP signer: the signing key, its certificate and the CA that issued it.
 *
 * @param privateKey         the OCSP signing key
 * @param certificate        the OCSP signer certificate (carries {@code id-kp-OCSPSigning})
 * @param issuer             the CA certificate that issued the signer and whose certificates are answered for
 * @param signatureAlgorithm JCA signature algorithm matching the key, e.g. {@code SHA256withRSA}
 */
public record OcspSigner(
        PrivateKey privateKey,
        X509CertificateHolder certificate,
        X509CertificateHolder issuer,
        String signatureAlgorithm) {

    /**
     * Creates a new content signer. Content signers are stateful, so one is created per response.
     *
     * @return a content signer for the OCSP signing key
     * @throws OperatorCreationException if the signer cannot be created
     */
    public ContentSigner contentSigner() throws OperatorCreationException {
        return new JcaContentSignerBuilder(signatureAlgorithm).build(privateKey);
    }

    /**
     * @param instant the point in time to check
     * @return whether the signer certificate is valid at the given instant
     */
    public boolean isValidAt(Instant instant) {
        return !instant.isBefore(certificate.getNotBefore().toInstant())
                && !instant.isAfter(certificate.getNotAfter().toInstant());
    }
}
