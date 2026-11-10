/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.utils.cryptography;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The three ways a certificate serial number is written, and conversions between them. Every conversion goes
 * through {@link BigInteger}, so leading zeros and separators never make two forms of the same serial differ.
 *
 * <ul>
 *   <li>as a caller writes it: hex bytes separated by colons or hyphens, as Vault issues it ({@link #parse});
 *   <li>as Vault keys its certificates: lowercase hex bytes separated by colons ({@link #toVault});
 *   <li>as {@code organisation_certificate.serial_number} stores it: lowercase hex without separators or leading
 *       zeros ({@link #toStored}).
 * </ul>
 */
public final class CertificateSerials {

    /**
     * A serial as Vault issues it: one to twenty hex bytes (RFC 5280 allows at most 20 octets), separated by
     * colons or hyphens.
     */
    public static final String SEPARATED_HEX_PATTERN = "^[0-9a-fA-F]{2}([:-][0-9a-fA-F]{2}){0,19}$";

    private static final Pattern SEPARATED_HEX = Pattern.compile(SEPARATED_HEX_PATTERN);
    private static final HexFormat VAULT_FORMAT = HexFormat.ofDelimiter(":");

    private CertificateSerials() {}

    /**
     * Parses a serial written as colon- or hyphen-separated hex bytes, e.g. {@code 39:dd:2e} or {@code 39-DD-2E}.
     *
     * @param serial the serial as the caller wrote it
     * @return the serial number
     * @throws IllegalArgumentException if the serial does not match {@link #SEPARATED_HEX_PATTERN}
     */
    public static BigInteger parse(String serial) {
        if (serial == null || !SEPARATED_HEX.matcher(serial).matches()) {
            throw new IllegalArgumentException("Serial number must be hex bytes separated by colons or hyphens");
        }
        return new BigInteger(serial.replaceAll("[:-]", ""), 16);
    }

    /**
     * Formats a serial the way Vault keys certificates: lowercase hex bytes separated by colons, without the
     * leading sign byte of the two's complement encoding.
     *
     * @param serial the certificate serial number
     * @return the Vault serial, e.g. {@code 1a:2b:3c}
     */
    public static String toVault(BigInteger serial) {
        byte[] bytes = serial.toByteArray();
        int from = (bytes.length > 1 && bytes[0] == 0) ? 1 : 0;
        return VAULT_FORMAT.formatHex(bytes, from, bytes.length);
    }

    /**
     * Formats a serial the way {@code organisation_certificate.serial_number} stores it.
     *
     * @param serial the certificate serial number
     * @return lowercase hex without separators or leading zeros, e.g. {@code 1a2b3c}
     */
    public static String toStored(BigInteger serial) {
        return serial.toString(16);
    }
}
