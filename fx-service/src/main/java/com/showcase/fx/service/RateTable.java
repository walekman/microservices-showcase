package com.showcase.fx.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Every rate the provider published for one base currency on one day. This is the unit the
 * provider returns and the unit Redis caches (one JSON value per base), so a single provider call
 * serves every quote from that base.
 */
public record RateTable(String base, LocalDate asOf, Map<String, BigDecimal> rates) {
}
