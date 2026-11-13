/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.bouncycastle.cert.X509CertificateHolder;

/**
 * A certificate authority whose certificates have a status, with the hash that names it in the status cache.
 *
 * @param keyHash     lowercase hex SHA-256 of the authority's public key. Computed from the authority's own
 *                    certificate, never taken from a request, so one certificate has one cache entry whichever hash
 *                    an OCSP request used.
 * @param certificate the authority's certificate
 */
public record CertificateIssuer(String keyHash, X509CertificateHolder certificate) {

    private static final String KEY_HASH_ALGORITHM = "SHA-256";

    public static CertificateIssuer of(X509CertificateHolder certificate) {
        try {
            byte[] key =
                    certificate.getSubjectPublicKeyInfo().getPublicKeyData().getBytes();
            return new CertificateIssuer(
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance(KEY_HASH_ALGORITHM)
                                    .digest(key)),
                    certificate);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(KEY_HASH_ALGORITHM + " is not available", e);
        }
    }
}
