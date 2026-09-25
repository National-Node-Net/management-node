/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.certificate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateEvent;

/**
 * Repository interface for managing {@link CertificateEvent} entities.
 *
 * This interface provides CRUD operations and query methods for interacting with
 * the underlying database layer as it extends the {@link JpaRepository} interface.
 * It facilitates persistence and retrieval of CertificateEvent data from the related
 * database table.
 *
 * Primary focus is on the {@link CertificateEvent} entity with the identifier type {@link Long}.
 */
@Repository
public interface CertificateEventRepository extends JpaRepository<CertificateEvent, Long> {}
