/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

/**
 * The PKI backend refused the Management Node's identity: its policy does not allow the request, or it could
 * not log in. Never retried, because repeated failed logins lock the identity out.
 */
public class PkiPermissionDeniedException extends PkiRejectedException {

    public PkiPermissionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
