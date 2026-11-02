/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import java.util.Optional;

/**
 * Provides the delegated OCSP signer (key, certificate and issuing CA) used to sign OCSP responses.
 */
public interface OcspSignerService {

    /**
     * Returns the current signer if one is loaded and its certificate is valid now.
     *
     * @return the current signer, or empty if none is usable
     */
    Optional<OcspSigner> currentSigner();

    /**
     * Reloads the signer from its source. Failures are logged and leave the previous signer in place.
     */
    void refresh();
}
