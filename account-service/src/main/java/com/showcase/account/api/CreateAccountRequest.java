package com.showcase.account.api;

import com.showcase.account.domain.SupportedCurrency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank String ownerName,
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 15, fraction = 2) BigDecimal initialBalance,
        // An unknown code fails JSON binding (400 MALFORMED_REQUEST); a missing one fails @NotNull (400 VALIDATION_FAILED).
        @NotNull SupportedCurrency currency) {
}
