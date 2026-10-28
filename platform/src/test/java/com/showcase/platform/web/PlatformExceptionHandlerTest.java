package com.showcase.platform.web;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StubController())
            .setControllerAdvice(new ServiceHandler())
            .build();

    @Test
    void malformedBodyIsMalformedRequest() throws Exception {
        expect(mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{not json")),
                400, "MALFORMED_REQUEST", "Malformed request body");
    }

    @Test
    void invalidBodyIsValidationFailedNamingTheField() throws Exception {
        expect(mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}")),
                400, "VALIDATION_FAILED", "name must not be blank");
    }

    @Test
    void missingHeaderIsValidationFailedNamingTheHeader() throws Exception {
        expect(mvc.perform(get("/header")), 400, "VALIDATION_FAILED", "Missing required header: Idempotency-Key");
    }

    @Test
    void missingQueryParameterIsValidationFailedNamingTheParameter() throws Exception {
        expect(mvc.perform(get("/param")), 400, "VALIDATION_FAILED", "Missing required parameter: accountId");
    }

    @Test
    void wrongTypeIsMalformedRequestNamingTheParameter() throws Exception {
        expect(mvc.perform(get("/path/not-a-uuid")), 400, "MALFORMED_REQUEST", "Invalid value for parameter: id");
    }

    @Test
    void constraintViolationIsValidationFailedNamingTheLastPathSegment() throws Exception {
        expect(mvc.perform(get("/constraint")), 400, "VALIDATION_FAILED", "name must not be blank");
    }

    @Test
    void unexpectedExceptionIsInternalErrorWithoutLeakingTheMessage() throws Exception {
        expect(mvc.perform(get("/boom")), 500, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    @Test
    void otherMvcErrorsStillCarryACode() throws Exception {
        mvc.perform(put("/boom"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void aServicesOwnHandlerWinsOverTheCatchAll() throws Exception {
        expect(mvc.perform(get("/domain")), 404, "THING_NOT_FOUND", "no such thing");
    }

    @Test
    void problemsCarryTheTypeUriTitleAndTimestamp() throws Exception {
        mvc.perform(get("/domain"))
                .andExpect(jsonPath("$.type").value("https://showcase.example/errors/thing-not-found"))
                .andExpect(jsonPath("$.title").value("Thing not found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    private static void expect(ResultActions result, int status, String code, String detail) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").value(detail));
    }

    record Body(@NotBlank String name) {
    }

    static class ThingNotFoundException extends RuntimeException {
        ThingNotFoundException() {
            super("no such thing");
        }
    }

    @RestControllerAdvice
    static class ServiceHandler extends PlatformExceptionHandler {

        @ExceptionHandler(ThingNotFoundException.class)
        public ProblemDetail handleNotFound(ThingNotFoundException ex) {
            return Problems.of(HttpStatus.NOT_FOUND, "THING_NOT_FOUND", "Thing not found", ex.getMessage());
        }
    }

    @RestController
    static class StubController {

        @PostMapping("/body")
        void body(@Valid @RequestBody Body body) {
        }

        @GetMapping("/header")
        void header(@RequestHeader("Idempotency-Key") String key) {
        }

        @GetMapping("/param")
        void param(@RequestParam UUID accountId) {
        }

        @GetMapping("/path/{id}")
        void path(@PathVariable UUID id) {
        }

        @GetMapping("/constraint")
        void constraint() {
            try (var factory = Validation.buildDefaultValidatorFactory()) {
                throw new ConstraintViolationException(factory.getValidator().validate(new Body("")));
            }
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("db password is hunter2");
        }

        @GetMapping("/domain")
        void domain() {
            throw new ThingNotFoundException();
        }
    }
}
