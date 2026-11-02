/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OcspResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspResponderService;

@ExtendWith(MockitoExtension.class)
class OcspControllerTest {

    private static final byte[] REQUEST = {0x30, 0x01, 0x00};
    private static final byte[] RESPONSE = {0x30, 0x03, 0x0a, 0x01, 0x00};

    @Mock
    private OcspResponderService responderService;

    private OcspController controller;

    @BeforeEach
    void setUp() {
        controller = new OcspController(responderService);
    }

    private static MockHttpServletRequest httpRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        return request;
    }

    @Test
    void ocsp_successfulResponse_shouldSetContentTypeAndMaxAgeUntilNextUpdate() {
        when(responderService.respond(REQUEST, "10.1.2.3"))
                .thenReturn(new OcspResponseDTO(RESPONSE, 0, Instant.now().plus(Duration.ofMinutes(5))));

        ResponseEntity<byte[]> response = controller.ocsp(REQUEST, httpRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(
                "application/ocsp-response",
                response.getHeaders().getContentType().toString());
        assertArrayEquals(RESPONSE, response.getBody());
        String cacheControl = response.getHeaders().getCacheControl();
        assertNotNull(cacheControl);
        assertTrue(cacheControl.matches("max-age=(29\\d|300), must-revalidate, no-transform, public"), cacheControl);
    }

    @Test
    void ocsp_nextUpdateInPast_shouldNotUseNegativeMaxAge() {
        when(responderService.respond(REQUEST, "10.1.2.3"))
                .thenReturn(new OcspResponseDTO(RESPONSE, 0, Instant.now().minusSeconds(5)));

        ResponseEntity<byte[]> response = controller.ocsp(REQUEST, httpRequest());

        assertTrue(response.getHeaders().getCacheControl().startsWith("max-age=0"));
    }

    @Test
    void ocsp_errorResponse_shouldNotBeCached() {
        when(responderService.respond(REQUEST, "10.1.2.3")).thenReturn(new OcspResponseDTO(RESPONSE, 3, null));

        ResponseEntity<byte[]> response = controller.ocsp(REQUEST, httpRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(
                "application/ocsp-response",
                response.getHeaders().getContentType().toString());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void ocsp_overHttp_shouldAcceptAnyContentTypeAndReturnOcspResponse() throws Exception {
        when(responderService.respond(any(), any())).thenReturn(new OcspResponseDTO(RESPONSE, 1, null));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/v1/certificate/ocsp")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("garbage"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/ocsp-response"))
                .andExpect(content().bytes(RESPONSE));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(responderService).respond(body.capture(), eq("127.0.0.1"));
        assertArrayEquals("garbage".getBytes(), body.getValue());
    }

    @Test
    void ocsp_overHttpWithOcspRequestContentType_shouldPassBody() throws Exception {
        when(responderService.respond(any(), any())).thenReturn(new OcspResponseDTO(RESPONSE, 0, Instant.now()));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/v1/certificate/ocsp")
                        .contentType("application/ocsp-request")
                        .content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/ocsp-response"));

        verify(responderService).respond(eq(REQUEST), any());
    }
}
