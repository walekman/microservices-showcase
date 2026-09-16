package com.showcase.transfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Minimal binding for Fraud Service's RFC 7807 error body -- see AccountProblem's javadoc. */
@JsonIgnoreProperties(ignoreUnknown = true)
record FraudProblem(String code, String detail) {
}
