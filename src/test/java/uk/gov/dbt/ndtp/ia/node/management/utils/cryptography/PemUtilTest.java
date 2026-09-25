/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.utils.cryptography;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiException;

class PemUtilTest {

    @Test
    void parsePublicKey_withInvalidPem_shouldThrowPkiException() {
        String invalidPem = "-----BEGIN PUBLIC KEY-----\nINVALID\n-----END PUBLIC KEY-----\n";
        assertThrows(PkiException.class, () -> PemUtil.parsePublicKey(invalidPem));
    }

    @Test
    void parseCertificate_withInvalidPem_shouldThrowPkiException() {
        String invalidPem = "-----BEGIN CERTIFICATE-----\nINVALID\n-----END CERTIFICATE-----\n";
        assertThrows(PkiException.class, () -> PemUtil.parseCertificate(invalidPem));
    }

    @Test
    void roundTripKeyParsing_shouldSucceed() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        PrivateKey priv = kp.getPrivate();
        PublicKey pub = kp.getPublic();

        String privPem = PemUtil.toPem("PRIVATE KEY", priv.getEncoded());
        String pubPem = PemUtil.toPem("PUBLIC KEY", pub.getEncoded());

        PrivateKey parsedPriv = PemUtil.parsePkcs8PrivateKey(privPem);
        PublicKey parsedPub = PemUtil.parsePublicKey(pubPem);

        assertEquals(priv.getAlgorithm(), parsedPriv.getAlgorithm());
        assertEquals(pub.getAlgorithm(), parsedPub.getAlgorithm());
        assertArrayEquals(priv.getEncoded(), parsedPriv.getEncoded());
        assertArrayEquals(pub.getEncoded(), parsedPub.getEncoded());
    }
}
