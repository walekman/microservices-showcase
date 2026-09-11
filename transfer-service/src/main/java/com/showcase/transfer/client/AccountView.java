package com.showcase.transfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** The subset of Account Service's response this service actually reads. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountView(UUID id, BigDecimal balance) {
}
