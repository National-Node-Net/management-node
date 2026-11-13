/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import java.io.StringReader;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.PemUtil;

/**
 * Test PKI for OCSP tests: a CA, delegated OCSP signers and leaf certificates built with Bouncy Castle.
 * Key pairs are generated once per JVM because RSA key generation is slow.
 */
public final class OcspTestPki {

    public static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    public static final KeyPair CA_KEYS = rsaKeyPair();
    public static final KeyPair OTHER_CA_KEYS = rsaKeyPair();
    public static final KeyPair SIGNER_RSA_KEYS = rsaKeyPair();
    public static final KeyPair SIGNER_EC_KEYS = ecKeyPair();
    public static final KeyPair LEAF_KEYS = rsaKeyPair();

    public static final X509CertificateHolder CA = caCertificate("CN=Federation Test CA", CA_KEYS);
    public static final X509CertificateHolder OTHER_CA = caCertificate("CN=Other Test CA", OTHER_CA_KEYS);

    private OcspTestPki() {}

    public static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static X509CertificateHolder caCertificate(String subject, KeyPair keys) {
        X500Name name = new X500Name(subject);
        return build(
                name,
                keys,
                name,
                keys.getPublic(),
                BigInteger.ONE,
                NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(365)),
                true,
                false);
    }

    /** OCSP signer issued by {@link #CA}, valid one hour either side of {@link #NOW}. */
    public static X509CertificateHolder signerCertificate(KeyPair signerKeys) {
        return signerCertificate(
                signerKeys, CA, CA_KEYS, NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), true);
    }

    public static X509CertificateHolder signerCertificate(
            KeyPair signerKeys,
            X509CertificateHolder issuer,
            KeyPair issuerKeys,
            Instant notBefore,
            Instant notAfter,
            boolean ocspSigningEku) {
        return build(
                issuer.getSubject(),
                issuerKeys,
                new X500Name("CN=OCSP Signer"),
                signerKeys.getPublic(),
                BigInteger.valueOf(1000),
                notBefore,
                notAfter,
                false,
                ocspSigningEku);
    }

    /**
     * Issues a certificate for the subject and public key of a PEM CSR, without the OCSP signing EKU, as Vault does
     * with a role that does not grant it.
     */
    public static X509CertificateHolder certificateFromCsr(
            String csrPem, X509CertificateHolder issuer, KeyPair issuerKeys, Instant notBefore, Instant notAfter) {
        try (PEMParser parser = new PEMParser(new StringReader(csrPem))) {
            var csr = new JcaPKCS10CertificationRequest((PKCS10CertificationRequest) parser.readObject());
            return build(
                    issuer.getSubject(),
                    issuerKeys,
                    csr.getSubject(),
                    csr.getPublicKey(),
                    BigInteger.valueOf(2000),
                    notBefore,
                    notAfter,
                    false,
                    false);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static X509CertificateHolder leafCertificate(
            BigInteger serial, X509CertificateHolder issuer, KeyPair issuerKeys) {
        return build(
                issuer.getSubject(),
                issuerKeys,
                new X500Name("CN=node-" + serial),
                LEAF_KEYS.getPublic(),
                serial,
                NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(30)),
                false,
                false);
    }

    public static OcspSigner rsaSigner() {
        return new OcspSigner(SIGNER_RSA_KEYS.getPrivate(), signerCertificate(SIGNER_RSA_KEYS), CA, "SHA256withRSA");
    }

    public static OcspSigner ecSigner() {
        return new OcspSigner(SIGNER_EC_KEYS.getPrivate(), signerCertificate(SIGNER_EC_KEYS), CA, "SHA256withECDSA");
    }

    public static CertificateID certId(X509CertificateHolder issuer, BigInteger serial) {
        return certId(issuer, serial, CertificateID.HASH_SHA1);
    }

    public static CertificateID certId(
            X509CertificateHolder issuer, BigInteger serial, AlgorithmIdentifier hashAlgorithm) {
        try {
            return new CertificateID(
                    new JcaDigestCalculatorProviderBuilder().build().get(hashAlgorithm), issuer, serial);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds a DER-encoded OCSP request for the given certificate IDs, with an optional nonce. */
    public static byte[] request(byte[] nonce, CertificateID... ids) {
        try {
            OCSPReqBuilder builder = new OCSPReqBuilder();
            for (CertificateID id : ids) {
                builder.addRequest(id);
            }
            if (nonce != null) {
                builder.setRequestExtensions(new Extensions(nonceExtension(nonce)));
            }
            return builder.build().getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Extension nonceExtension(byte[] nonce) {
        try {
            return new Extension(
                    OCSPObjectIdentifiers.id_pkix_ocsp_nonce, false, new DEROctetString(new DEROctetString(nonce)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static X509Certificate toX509(X509CertificateHolder holder) {
        try {
            return new JcaX509CertificateConverter().getCertificate(holder);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String toPem(X509CertificateHolder holder) {
        try {
            return PemUtil.toPem("CERTIFICATE", holder.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String privateKeyPem(KeyPair keys) {
        return PemUtil.toPem("PRIVATE KEY", keys.getPrivate().getEncoded());
    }

    /** Builds a PEM CSR for the given subject, signed with SHA256withRSA or SHA256withECDSA. */
    public static String csrPem(String subject, KeyPair keys) {
        try {
            var csr = new JcaPKCS10CertificationRequestBuilder(new X500Name(subject), keys.getPublic())
                    .build(new JcaContentSignerBuilder(signatureAlgorithm(keys)).build(keys.getPrivate()));
            return PemUtil.toPem("CERTIFICATE REQUEST", csr.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static X509CertificateHolder build(
            X500Name issuer,
            KeyPair issuerKeys,
            X500Name subject,
            PublicKey subjectKey,
            BigInteger serial,
            Instant notBefore,
            Instant notAfter,
            boolean ca,
            boolean ocspSigningEku) {
        try {
            JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    issuer, serial, Date.from(notBefore), Date.from(notAfter), subject, subjectKey);
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
            builder.addExtension(
                    Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(subjectKey));
            builder.addExtension(
                    Extension.authorityKeyIdentifier,
                    false,
                    extUtils.createAuthorityKeyIdentifier(issuerKeys.getPublic()));
            if (ca) {
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            } else {
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            }
            if (ocspSigningEku) {
                builder.addExtension(
                        Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_OCSPSigning));
            }
            return builder.build(
                    new JcaContentSignerBuilder(signatureAlgorithm(issuerKeys)).build(issuerKeys.getPrivate()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String signatureAlgorithm(KeyPair keys) {
        return "EC".equals(keys.getPublic().getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
    }
}
