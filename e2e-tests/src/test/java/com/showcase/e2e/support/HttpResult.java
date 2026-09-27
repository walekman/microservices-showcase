package com.showcase.e2e.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.UUID;

/** A response as the tests see it: the status and the raw body, parsed as JSON on demand. */
public record HttpResult(int status, String raw) {

    public JsonNode json() {
        if (raw.isBlank()) {
            return Http.JSON.missingNode();
        }
        try {
            return Http.JSON.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new AssertionError("Response body is not JSON: " + raw, ex);
        }
    }

    public String text(String field) {
        return json().path(field).asText(null);
    }

    public UUID uuid(String field) {
        return UUID.fromString(json().path(field).asText());
    }

    public BigDecimal decimal(String field) {
        return json().path(field).decimalValue();
    }

    public HttpResult expect(int expected) {
        if (status != expected) {
            throw new AssertionError("Expected HTTP " + expected + " but got " + status + ": " + raw);
        }
        return this;
    }
}
