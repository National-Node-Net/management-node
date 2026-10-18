/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateEvent;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateEventType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.OrganisationCertificate;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.certificate.CertificateEventRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.certificate.OrganisationCertificateRepository;

class CertificateEventServiceImplTest {

    @Mock
    private CertificateEventRepository eventRepository;

    @Mock
    private OrganisationCertificateRepository certificateRepository;

    private CertificateEventServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CertificateEventServiceImpl(eventRepository, certificateRepository);
    }

    @Test
    void recordEvent_savesEventWithCorrectFields() {
        OrganisationCertificate certRef = new OrganisationCertificate();
        certRef.setId(5L);
        when(certificateRepository.getReferenceById(5L)).thenReturn(certRef);
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Instant before = Instant.now();
        service.recordEvent(5L, CertificateType.AUTOMATED, CertificateEventType.RENEWED, "client-1");

        ArgumentCaptor<CertificateEvent> captor = ArgumentCaptor.forClass(CertificateEvent.class);
        verify(eventRepository).save(captor.capture());

        CertificateEvent saved = captor.getValue();
        assertThat(saved.getOrganisationCertificate()).isEqualTo(certRef);
        assertThat(saved.getType()).isEqualTo(CertificateType.AUTOMATED);
        assertThat(saved.getEventType()).isEqualTo(CertificateEventType.RENEWED);
        assertThat(saved.getPerformedBy()).isEqualTo("client-1");
        assertThat(saved.getEventTime().toInstant()).isBetween(before, Instant.now());
    }
}
