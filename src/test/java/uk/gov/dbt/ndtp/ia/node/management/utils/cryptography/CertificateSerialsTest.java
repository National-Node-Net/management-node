/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.utils.cryptography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CertificateSerialsTest {

    @ParameterizedTest
    @CsvSource({
        "39:dd:2e, 39dd2e",
        "39-DD-2E, 39dd2e",
        "39:dd-2e, 39dd2e",
        "0a:01, a01",
        "00:ff, ff",
        "ff, ff",
        "39:dd:2e:90:b7:23:1f:8d:d3:7d:31:c5:1b:da:84:d0:5b:65:31:58, 39dd2e90b7231f8dd37d31c51bda84d05b653158"
    })
    void parse_shouldAcceptColonOrHyphenSeparatedHexInAnyCase(String serial, String hex) {
        assertEquals(new BigInteger(hex, 16), CertificateSerials.parse(serial));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "39dd2e",
                "39:dd:2",
                "39::dd",
                ":39:dd",
                "39:dd:",
                "zz:01",
                "39 dd",
                "39:dd:2e:90:b7:23:1f:8d:d3:7d:31:c5:1b:da:84:d0:5b:65:31:58:01"
            })
    void parse_shouldRejectAnythingElse(String serial) {
        assertThrows(IllegalArgumentException.class, () -> CertificateSerials.parse(serial));
    }

    @ParameterizedTest
    @CsvSource({
        "1, 01",
        "ff, ff",
        "0102, 01:02",
        "80, 80",
        "a01, 0a:01",
        "3a5f9c, 3a:5f:9c",
        "7fffffffffffffff01, 7f:ff:ff:ff:ff:ff:ff:ff:01"
    })
    void toVault_shouldFormatAsColonSeparatedHexWithoutTheSignByte(String hex, String expected) {
        assertEquals(expected, CertificateSerials.toVault(new BigInteger(hex, 16)));
    }

    @Test
    void toStored_shouldMatchHowCertificateRecordsStoreSerials() {
        // CertificateSigningProviderImpl stores X509Certificate#getSerialNumber in this form.
        assertEquals("a01", CertificateSerials.toStored(new BigInteger("0a01", 16)));
        assertEquals("39dd2e", CertificateSerials.toStored(CertificateSerials.parse("39:DD:2E")));
    }

    @Test
    void forms_shouldRoundTrip() {
        BigInteger serial = new BigInteger("00f3a5", 16);

        assertEquals(serial, CertificateSerials.parse(CertificateSerials.toVault(serial)));
    }
}
