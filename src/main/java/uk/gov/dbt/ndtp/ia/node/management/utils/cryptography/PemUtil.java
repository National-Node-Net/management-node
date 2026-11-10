/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.utils.cryptography;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiException;

/**
 * Utility class for PEM (Privacy-Enhanced Mail) format operations.
 * Provides methods for parsing keys and certificates from PEM strings and converting to PEM format.
 */
public class PemUtil {

    private static final String KEY_ALG = "RSA";

    private PemUtil() {}
    /**
     * Parses a PKCS#8 encoded private key from a PEM string.
     *
     * @param pem the PEM encoded private key string
     * @return the parsed PrivateKey
     * @throws PkiException if the key cannot be parsed or the algorithm is unavailable
     */
    public static PrivateKey parsePkcs8PrivateKey(String pem) {
        try {
            byte[] der = decodePem(pem);
            return KeyFactory.getInstance(KEY_ALG).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new PkiException("Failed to parse private key", e);
        }
    }

    /**
     * Parses an X.509 encoded public key from a PEM string.
     *
     * @param pem the PEM encoded public key string
     * @return the parsed PublicKey
     * @throws PkiException if the key cannot be parsed or the algorithm is unavailable
     */
    public static PublicKey parsePublicKey(String pem) {
        try {
            byte[] der = decodePem(pem);
            return KeyFactory.getInstance(KEY_ALG).generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new PkiException("Failed to parse public key", e);
        }
    }

    /**
     * Decodes a PEM string by removing headers, footers, and whitespace.
     *
     * @param pem the PEM string to decode
     * @return the decoded byte array
     */
    private static byte[] decodePem(String pem) {
        String cleaned = pem.replaceAll("-----BEGIN ([A-Z ]+)-----", "")
                .replaceAll("-----END ([A-Z ]+)-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(cleaned);
    }

    /**
     * Converts a DER encoded byte array to a PEM string.
     *
     * @param type the PEM type label (e.g., "PRIVATE KEY", "CERTIFICATE")
     * @param der the DER encoded byte array
     * @return the PEM formatted string
     */
    public static String toPem(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    /**
     * Parses an X.509 certificate from a PEM string.
     *
     * @param pem the PEM encoded certificate string
     * @return the parsed X509Certificate
     * @throws PkiException if the certificate cannot be parsed or the factory is unavailable
     */
    public static X509Certificate parseCertificate(String pem) {
        try {
            byte[] der = decodePem(pem);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException e) {
            throw new PkiException("Failed to parse certificate", e);
        }
    }
}
