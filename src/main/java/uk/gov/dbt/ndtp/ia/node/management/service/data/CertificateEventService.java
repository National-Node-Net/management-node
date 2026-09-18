/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateEventType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;

/**
 * Service interface for recording certificate lifecycle events.
 */
public interface CertificateEventService {

    /**
     * Records a certificate event for audit purposes.
     *
     * @param organisationCertificateId the ID of the organisation certificate
     * @param type the certificate type at the time of the event
     * @param eventType the type of event being recorded
     * @param performedBy identifier of the actor that triggered the event
     */
    void recordEvent(
            Long organisationCertificateId, CertificateType type, CertificateEventType eventType, String performedBy);
}
