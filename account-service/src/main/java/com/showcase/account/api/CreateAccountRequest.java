package com.showcase.account.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank String ownerName,
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 15, fraction = 2) BigDecimal initialBalance) {
}
