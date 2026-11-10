/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OcspResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspResponderService;
import uk.gov.dbt.ndtp.ia.node.management.utils.http.CacheControlUtil;

/**
 * OCSP responder endpoint returning certificate revocation status from Vault PKI.
 */
@RestController
@RequestMapping("/api/v1/certificate")
@Tag(name = "Certificate", description = "Endpoints for certificate management.")
public class OcspController {

    static final MediaType OCSP_RESPONSE = MediaType.parseMediaType("application/ocsp-response");

    private final OcspResponderService responderService;

    public OcspController(OcspResponderService responderService) {
        this.responderService = responderService;
    }

    /**
     * Answers a DER-encoded OCSP request.
     *
     * <p>Any request body is accepted regardless of its {@code Content-Type}; bodies that are not a valid OCSP
     * request are answered with the OCSP status {@code malformedRequest} rather than an HTTP error.
     *
     * @param body    the DER-encoded OCSP request
     * @param request the HTTP request, used for the requester IP
     * @return the DER-encoded OCSP response
     */
    @PostMapping("/ocsp")
    @PreAuthorize("hasAuthority('ROLE_management-node:management-node-access')")
    @Operation(
            summary = "Check certificate status (OCSP)",
            description =
                    "Returns the revocation status of certificates issued by the federation CA as a DER-encoded"
                            + " OCSP response, signed by the delegated OCSP signer certificate which is included in the response.",
            security = {@SecurityRequirement(name = "bearerAuth")})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            description = "DER-encoded OCSP request",
            content =
                    @Content(
                            mediaType = "application/ocsp-request",
                            schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(
            responseCode = "200",
            description = "DER-encoded OCSP response (including OCSP error statuses)",
            content =
                    @Content(
                            mediaType = "application/ocsp-response",
                            schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public ResponseEntity<byte[]> ocsp(@RequestBody(required = false) byte[] body, HttpServletRequest request) {
        OcspResponseDTO result = responderService.respond(body, request.getRemoteAddr());
        return ResponseEntity.ok()
                .contentType(OCSP_RESPONSE)
                .cacheControl(CacheControlUtil.forOcspResponse(result))
                .body(result.getEncoded());
    }
}
