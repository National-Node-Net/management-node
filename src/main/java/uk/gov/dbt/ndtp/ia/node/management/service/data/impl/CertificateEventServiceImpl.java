/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateEvent;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateEventType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.OrganisationCertificate;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.certificate.CertificateEventRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.certificate.OrganisationCertificateRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.CertificateEventService;

/**
 * Implementation of {@link CertificateEventService} that persists events
 * via {@link CertificateEventRepository}.
 */
@Service
public class CertificateEventServiceImpl implements CertificateEventService {

    private final CertificateEventRepository eventRepository;
    private final OrganisationCertificateRepository certificateRepository;

    public CertificateEventServiceImpl(
            CertificateEventRepository eventRepository, OrganisationCertificateRepository certificateRepository) {
        this.eventRepository = eventRepository;
        this.certificateRepository = certificateRepository;
    }

    @Override
    public void recordEvent(
            Long organisationCertificateId, CertificateType type, CertificateEventType eventType, String performedBy) {
        OrganisationCertificate certificate = certificateRepository.getReferenceById(organisationCertificateId);

        CertificateEvent event = new CertificateEvent();
        event.setOrganisationCertificate(certificate);
        event.setType(type);
        event.setEventType(eventType);
        event.setEventTime(Timestamp.from(Instant.now()));
        event.setPerformedBy(performedBy);

        eventRepository.save(event);
    }
}
