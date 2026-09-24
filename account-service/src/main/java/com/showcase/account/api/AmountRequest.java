package com.showcase.account.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record AmountRequest(
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
        // Optional: the currency the caller computed amount in. Absent only on a replay of a
        // pre-Phase-12 transfer's leg. See AccountService.requireCurrency.
        @Pattern(regexp = "[A-Z]{3}") String currency) {
}
