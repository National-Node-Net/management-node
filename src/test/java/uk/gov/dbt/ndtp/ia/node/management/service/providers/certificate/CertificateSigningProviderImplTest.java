/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateSigningException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.SignCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateEventType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.organisation.OrganisationRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.CertificateEventService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationCertificateService;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.PemUtil;

class CertificateSigningProviderImplTest {

    @Mock
    private VaultPkiService vaultPkiService;

    @Mock
    private OrganisationCertificateService certificateService;

    @Mock
    private CertificateEventService eventService;

    @Mock
    private OrganisationRepository organisationRepository;

    private CertificateSigningProviderImpl provider;
    private MockedStatic<PemUtil> pemUtilMock;

    private static final String BOOTSTRAP_TTL = "2h";
    private static final String BOOTSTRAP_OID = "1.3.6.1.4.1.32473.1.1";
    private static final String BOOTSTRAP_OTHER_SANS = BOOTSTRAP_OID + ";UTF8:bootstrap";
    private static final String BOOTSTRAP_CSR =
            "-----BEGIN CERTIFICATE REQUEST-----\nMIIBtest\n-----END CERTIFICATE REQUEST-----";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        provider = new CertificateSigningProviderImpl(
                vaultPkiService,
                certificateService,
                eventService,
                organisationRepository,
                BOOTSTRAP_TTL,
                BOOTSTRAP_OID);
        pemUtilMock = mockStatic(PemUtil.class);
    }

    @AfterEach
    void tearDown() {
        pemUtilMock.close();
    }

    private OrganisationCertificateDTO buildCert(CertificateType type, boolean automationEnabled, boolean renewable) {
        return OrganisationCertificateDTO.builder()
                .id(1L)
                .organisationId(10L)
                .type(type)
                .certificateAutomationEnabled(automationEnabled)
                .isRenewable(renewable)
                .build();
    }

    private SignCertResponseDTO buildSignResponse() {
        return SignCertResponseDTO.builder()
                .certificate("CERT_PEM")
                .serialNumber("abc123")
                .expiration(1735689600L)
                .caChain(List.of("CA_PEM"))
                .issuingCa("ISSUING_CA_PEM")
                .build();
    }

    private static final Instant NOT_BEFORE = Instant.parse("2025-01-01T00:00:00Z");

    private void mockPemParsing(String subjectDn) {
        X509Certificate x509 = mock(X509Certificate.class);
        when(x509.getSubjectX500Principal()).thenReturn(new X500Principal(subjectDn));
        when(x509.getNotBefore()).thenReturn(Date.from(NOT_BEFORE));
        when(x509.getSerialNumber()).thenReturn(new BigInteger("abc123", 16));
        pemUtilMock.when(() -> PemUtil.parseCertificate("CERT_PEM")).thenReturn(x509);
    }

    @Test
    void signAndRecord_updatesRecordAndCreatesEvent() {
        Instant before = Instant.now();
        OrganisationCertificateDTO cert = buildCert(CertificateType.BOOTSTRAP, true, true);
        when(certificateService.findByClientId("client-1")).thenReturn(Optional.of(cert));
        when(vaultPkiService.signCsr(eq("CSR"), any(), any())).thenReturn(buildSignResponse());
        when(certificateService.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockPemParsing("CN=test,O=NDTP");

        SignCertResponseDTO result = provider.signAndRecord("CSR", "client-1");

        assertThat(result.getSerialNumber()).isEqualTo("abc123");

        ArgumentCaptor<OrganisationCertificateDTO> captor = ArgumentCaptor.forClass(OrganisationCertificateDTO.class);
        verify(certificateService).save(captor.capture());

        OrganisationCertificateDTO saved = captor.getValue();
        assertThat(saved.getSerialNumber()).isEqualTo("abc123");
        assertThat(saved.getSubjectDn()).isEqualTo("CN=test,O=NDTP");
        assertThat(saved.getType()).isEqualTo(CertificateType.AUTOMATED);
        assertThat(saved.getRequestedAt()).isNotNull();
        assertThat(saved.getRequestedAt().toInstant()).isBetween(before, Instant.now());
        assertThat(saved.getIssuedAt()).isEqualTo(Timestamp.from(NOT_BEFORE));
        assertThat(saved.getExpiresAt()).isEqualTo(Timestamp.from(Instant.ofEpochSecond(1735689600L)));

        verify(eventService).recordEvent(1L, CertificateType.AUTOMATED, CertificateEventType.RENEWED, "client-1");
    }

    @Test
    void signAndRecord_noCertRecord_throwsException() {
        when(certificateService.findByClientId("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.signAndRecord("CSR", "unknown"))
                .isInstanceOf(CertificateSigningException.class)
                .hasMessageContaining("No certificate record");

        verifyNoInteractions(vaultPkiService);
    }

    @Test
    void signAndRecord_automationDisabled_throwsException() {
        OrganisationCertificateDTO cert = buildCert(CertificateType.MANUAL, false, false);
        when(certificateService.findByClientId("client-1")).thenReturn(Optional.of(cert));

        assertThatThrownBy(() -> provider.signAndRecord("CSR", "client-1"))
                .isInstanceOf(CertificateSigningException.class)
                .hasMessageContaining("automation is not enabled");

        verifyNoInteractions(vaultPkiService);
    }

    @Test
    void signAndRecord_notRenewable_throwsException() {
        OrganisationCertificateDTO cert = buildCert(CertificateType.AUTOMATED, true, false);
        when(certificateService.findByClientId("client-1")).thenReturn(Optional.of(cert));

        assertThatThrownBy(() -> provider.signAndRecord("CSR", "client-1"))
                .isInstanceOf(CertificateSigningException.class)
                .hasMessageContaining("not renewable");

        verifyNoInteractions(vaultPkiService);
    }

    @Test
    void signAndRecord_alreadyAutomated_staysAutomated() {
        OrganisationCertificateDTO cert = buildCert(CertificateType.AUTOMATED, true, true);
        when(certificateService.findByClientId("client-1")).thenReturn(Optional.of(cert));
        when(vaultPkiService.signCsr(eq("CSR"), any(), any())).thenReturn(buildSignResponse());
        when(certificateService.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockPemParsing("CN=test");

        provider.signAndRecord("CSR", "client-1");

        ArgumentCaptor<OrganisationCertificateDTO> captor = ArgumentCaptor.forClass(OrganisationCertificateDTO.class);
        verify(certificateService).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(CertificateType.AUTOMATED);
    }

    @Test
    void signAndRecord_parsesExpirationAsEpochSeconds() {
        OrganisationCertificateDTO cert = buildCert(CertificateType.AUTOMATED, true, true);
        when(certificateService.findByClientId("client-1")).thenReturn(Optional.of(cert));

        SignCertResponseDTO response = SignCertResponseDTO.builder()
                .certificate("CERT_PEM")
                .serialNumber("ser1")
                .expiration(1735689600L)
                .build();
        when(vaultPkiService.signCsr(eq("CSR"), any(), any())).thenReturn(response);
        when(certificateService.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockPemParsing("CN=test");

        provider.signAndRecord("CSR", "client-1");

        ArgumentCaptor<OrganisationCertificateDTO> captor = ArgumentCaptor.forClass(OrganisationCertificateDTO.class);
        verify(certificateService).save(captor.capture());
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(Timestamp.from(Instant.ofEpochSecond(1735689600L)));
    }

    private void setupBootstrapMocks(OrganisationCertificateDTO cert) {
        when(certificateService.findByOrganisationId(10L)).thenReturn(Optional.of(cert));
        when(vaultPkiService.signCsr(
                        BOOTSTRAP_CSR, Optional.empty(), Optional.of(BOOTSTRAP_TTL), Optional.of(BOOTSTRAP_OTHER_SANS)))
                .thenReturn(buildSignResponse());
        when(certificateService.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockPemParsing("CN=api.acme-digital.co.uk");
    }

    @Test
    void issueBootstrapPackage_success_returnsZipWithTwoEntries() throws Exception {
        OrganisationCertificateDTO cert = buildCert(CertificateType.MANUAL, true, false);
        setupBootstrapMocks(cert);

        byte[] zip = provider.issueBootstrapPackage(10L, BOOTSTRAP_CSR, "test-client");

        assertThat(zip).isNotNull().hasSizeGreaterThan(0);

        List<String> entryNames = new java.util.ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryNames.add(entry.getName());
            }
        }
        assertThat(entryNames).containsExactly("certificate.pem", "ca-chain.pem");
    }

    @Test
    void issueBootstrapPackage_success_nullCaChain_returnsEmptyCaChainPem() throws Exception {
        OrganisationCertificateDTO cert = buildCert(CertificateType.MANUAL, true, false);
        when(certificateService.findByOrganisationId(10L)).thenReturn(Optional.of(cert));

        SignCertResponseDTO responseWithNullChain = SignCertResponseDTO.builder()
                .certificate("CERT_PEM")
                .serialNumber("abc123")
                .expiration(1735689600L)
                .caChain(null)
                .issuingCa("ISSUING_CA_PEM")
                .build();
        when(vaultPkiService.signCsr(
                        BOOTSTRAP_CSR, Optional.empty(), Optional.of(BOOTSTRAP_TTL), Optional.of(BOOTSTRAP_OTHER_SANS)))
                .thenReturn(responseWithNullChain);
        when(certificateService.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockPemParsing("CN=api.acme-digital.co.uk");

        byte[] zip = provider.issueBootstrapPackage(10L, BOOTSTRAP_CSR, "test-client");

        assertThat(zip).isNotNull();

        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        assertThat(entries).containsKeys("certificate.pem", "ca-chain.pem");
        assertThat(entries.get("ca-chain.pem")).isEmpty();
    }

    @Test
    void issueBootstrapPackage_noCertRecord_createsOne() {
        when(certificateService.findByOrganisationId(99L)).thenReturn(Optional.empty());
        when(organisationRepository.existsById(99L)).thenReturn(true);

        OrganisationCertificateDTO created = OrganisationCertificateDTO.builder()
                .id(5L)
                .organisationId(99L)
                .type(CertificateType.MANUAL)
                .isRenewable(false)
                .build();
        when(certificateService.save(any())).thenReturn(created).thenAnswer(inv -> inv.getArgument(0));
        when(vaultPkiService.signCsr(
                        BOOTSTRAP_CSR, Optional.empty(), Optional.of(BOOTSTRAP_TTL), Optional.of(BOOTSTRAP_OTHER_SANS)))
                .thenReturn(buildSignResponse());
        mockPemParsing("CN=api.acme-digital.co.uk");

        byte[] zip = provider.issueBootstrapPackage(99L, BOOTSTRAP_CSR, "test-client");

        assertThat(zip).isNotNull();

        ArgumentCaptor<OrganisationCertificateDTO> captor = ArgumentCaptor.forClass(OrganisationCertificateDTO.class);
        verify(certificateService, atLeastOnce()).save(captor.capture());

        OrganisationCertificateDTO finalSave =
                captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(finalSave.getType()).isEqualTo(CertificateType.BOOTSTRAP);
        assertThat(finalSave.getIsRenewable()).isTrue();
        assertThat(finalSave.getSerialNumber()).isEqualTo("abc123");

        verify(eventService).recordEvent(5L, CertificateType.BOOTSTRAP, CertificateEventType.ISSUED, "test-client");
    }

    @Test
    void issueBootstrapPackage_organisationNotFound_throwsException() {
        when(certificateService.findByOrganisationId(999L)).thenReturn(Optional.empty());
        when(organisationRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> provider.issueBootstrapPackage(999L, BOOTSTRAP_CSR, "test-client"))
                .isInstanceOf(CertificateSigningException.class)
                .hasMessageContaining("Organisation not found");

        verify(vaultPkiService, never()).signCsr(any(), any(), any(), any());
    }

    @Test
    void issueBootstrapPackage_recordUpdatedWithBootstrapType() {
        Instant before = Instant.now();
        OrganisationCertificateDTO cert = buildCert(CertificateType.MANUAL, true, false);
        setupBootstrapMocks(cert);

        provider.issueBootstrapPackage(10L, BOOTSTRAP_CSR, "test-client");

        ArgumentCaptor<OrganisationCertificateDTO> captor = ArgumentCaptor.forClass(OrganisationCertificateDTO.class);
        verify(certificateService, atLeastOnce()).save(captor.capture());

        OrganisationCertificateDTO saved =
                captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(saved.getType()).isEqualTo(CertificateType.BOOTSTRAP);
        assertThat(saved.getIsRenewable()).isTrue();
        assertThat(saved.getSerialNumber()).isEqualTo("abc123");
        assertThat(saved.getSubjectDn()).isEqualTo("CN=api.acme-digital.co.uk");
        assertThat(saved.getRequestedAt()).isNotNull();
        assertThat(saved.getRequestedAt().toInstant()).isBetween(before, Instant.now());
        assertThat(saved.getIssuedAt()).isEqualTo(Timestamp.from(NOT_BEFORE));
        assertThat(saved.getExpiresAt()).isEqualTo(Timestamp.from(Instant.ofEpochSecond(1735689600L)));
    }

    @Test
    void issueBootstrapPackage_auditEventRecorded() {
        OrganisationCertificateDTO cert = buildCert(CertificateType.MANUAL, true, false);
        setupBootstrapMocks(cert);

        provider.issueBootstrapPackage(10L, BOOTSTRAP_CSR, "test-client");

        verify(eventService).recordEvent(1L, CertificateType.BOOTSTRAP, CertificateEventType.ISSUED, "test-client");
    }

    @Test
    void issueBootstrapPackage_usesConfiguredTtl() {
        OrganisationCertificateDTO cert = buildCert(CertificateType.MANUAL, true, false);
        setupBootstrapMocks(cert);

        provider.issueBootstrapPackage(10L, BOOTSTRAP_CSR, "test-client");

        verify(vaultPkiService)
                .signCsr(
                        BOOTSTRAP_CSR, Optional.empty(), Optional.of(BOOTSTRAP_TTL), Optional.of(BOOTSTRAP_OTHER_SANS));
    }

    @Test
    void issueBootstrapPackage_existingAutomatedCert_succeeds() {
        OrganisationCertificateDTO cert = buildCert(CertificateType.AUTOMATED, true, true);
        setupBootstrapMocks(cert);

        byte[] zip = provider.issueBootstrapPackage(10L, BOOTSTRAP_CSR, "test-client");

        assertThat(zip).isNotNull();

        ArgumentCaptor<OrganisationCertificateDTO> captor = ArgumentCaptor.forClass(OrganisationCertificateDTO.class);
        verify(certificateService, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getType())
                .isEqualTo(CertificateType.BOOTSTRAP);
    }
}
