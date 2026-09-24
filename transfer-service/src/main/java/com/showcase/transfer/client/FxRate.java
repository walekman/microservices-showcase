package com.showcase.transfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;

/** FX Service's GET /fx/rates answer. stale: the provider was down and this is its last known rate. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FxRate(String base, String quote, BigDecimal rate, LocalDate asOf, boolean stale) {
}
