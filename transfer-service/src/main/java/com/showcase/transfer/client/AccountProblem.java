package com.showcase.transfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Minimal binding for Account Service's RFC 7807 error body. Deliberately not
 * Spring's ProblemDetail: this parses with a plain ObjectMapper, with no dependency
 * on Boot's Jackson auto-configuration, so it works identically in tests.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record AccountProblem(String code, String detail) {
}
