package com.showcase.fx.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One rate, as GET /fx/rates returns it. asOf is the provider's publication date, not the request
 * time; stale means the provider was unreachable and this is the last rate it gave.
 */
public record FxQuote(String base, String quote, BigDecimal rate, LocalDate asOf, boolean stale) {
}
