/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drives real bad requests through MVC into {@link GlobalExceptionHandler}: every response names
 * only what the caller got wrong, never code details, and the full exception is logged under the
 * same error id.
 */
class GlobalExceptionHandlerWebTest {

    record Payload(@NotNull Long productId, @Size(max = 3) String code, List<@Size(max = 2) String> tags) {}

    @RestController
    static class SampleController {

        @PostMapping(value = "/sample", consumes = MediaType.APPLICATION_JSON_VALUE)
        Payload create(@Valid @RequestBody Payload payload) {
            return payload;
        }

        @GetMapping("/sample/{id}")
        String read(@PathVariable("id") Long id) {
            return "ok";
        }

        @GetMapping("/search")
        String search(@RequestParam("q") String q, @RequestHeader("X-Tenant") String tenant) {
            return "ok";
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("database password is hunter2");
        }
    }

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new SampleController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void validationFailure_namesTheFieldAndConstraintOnly() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/sample").contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"too-long\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.reasons").doesNotExist())
                .andReturn();

        String message = message(result);
        assertThat(message)
                .startsWith("Invalid request: ")
                .contains("productId must not be null")
                .contains("code size must be between 0 and 3");
        assertNoCodeDetails(message, "too-long");
        assertLoggedWithStackTrace(result, Level.WARN);
    }

    @Test
    void malformedJson_isReportedWithoutParserDetails() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/sample").contentType(MediaType.APPLICATION_JSON).content("{\"productId\": "))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(message(result)).isEqualTo("Invalid request body: malformed JSON");
        assertLoggedWithStackTrace(result, Level.WARN);
    }

    @Test
    void wrongFieldType_namesTheFieldPath() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/sample").contentType(MediaType.APPLICATION_JSON).content("{\"productId\": \"abc\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(message(result)).isEqualTo("Invalid request body: 'productId' has an invalid value");
        assertNoCodeDetails(message(result), "abc");
    }

    @Test
    void wrongNestedType_namesTheIndexedPath() throws Exception {
        MvcResult result = mockMvc.perform(post("/sample")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": 1, \"tags\": [\"ok\", {\"nested\": true}]}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(message(result)).isEqualTo("Invalid request body: 'tags[1]' has an invalid value");
    }

    @Test
    void missingBody_isReported() throws Exception {
        MvcResult result = mockMvc.perform(post("/sample").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(message(result)).isEqualTo("Invalid request body: request body is missing");
    }

    @Test
    void wrongPathVariableType_isABadRequest() throws Exception {
        MvcResult result = mockMvc.perform(get("/sample/abc"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(message(result)).isEqualTo("Invalid value for 'id'");
    }

    @Test
    void missingParameterAndHeader_areNamed() throws Exception {
        MvcResult missingParameter = mockMvc.perform(get("/search").header("X-Tenant", "t"))
                .andExpect(status().isBadRequest())
                .andReturn();
        MvcResult missingHeader = mockMvc.perform(get("/search").param("q", "x"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(message(missingParameter)).isEqualTo("Missing required parameter 'q'");
        assertThat(message(missingHeader)).isEqualTo("Missing required header 'X-Tenant'");
    }

    @Test
    void unsupportedMethod_isA405WithAllowHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/sample"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andReturn();

        assertThat(message(result)).isEqualTo("HTTP method GET is not supported for this endpoint");
    }

    @Test
    void unsupportedContentType_isA415() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/sample").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn();

        assertThat(message(result)).isEqualTo("Content type 'text/plain' is not supported");
    }

    @Test
    void unexpectedException_hidesItsMessageAndLogsAtError() throws Exception {
        MvcResult result = mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andReturn();

        assertThat(message(result)).isEqualTo(GlobalExceptionHandler.INTERNAL_ERROR_MESSAGE);
        assertNoCodeDetails(message(result), "hunter2");
        assertLoggedWithStackTrace(result, Level.ERROR);
    }

    private static String message(MvcResult result) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.message");
    }

    private static void assertNoCodeDetails(String message, String rejectedValue) {
        assertThat(message)
                .doesNotContain("uk.gov")
                .doesNotContain("org.springframework")
                .doesNotContain("com.fasterxml")
                .doesNotContain("Exception")
                .doesNotContain(rejectedValue);
    }

    /** The exception is logged with its stack trace, under the error id the caller received. */
    private void assertLoggedWithStackTrace(MvcResult result, Level level) throws Exception {
        String errorId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.errorId");
        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(level);
            assertThat(event.getFormattedMessage()).contains("error_id=" + errorId);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getStackTraceElementProxyArray())
                    .isNotEmpty();
        });
    }
}
