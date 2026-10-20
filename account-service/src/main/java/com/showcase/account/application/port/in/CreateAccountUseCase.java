package com.showcase.account.application.port.in;

import com.showcase.account.domain.Account;
import com.showcase.account.domain.SupportedCurrency;

import java.math.BigDecimal;
import java.util.UUID;

public interface CreateAccountUseCase {

    /** One account per owner: throws AccountAlreadyExistsException for an owner that has one. */
    Account createAccount(UUID ownerId, String ownerName, BigDecimal initialBalance, SupportedCurrency currency);
}
