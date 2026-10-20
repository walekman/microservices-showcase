package com.showcase.account.config;

import com.showcase.account.application.AccountApplicationService;
import com.showcase.account.application.port.out.AccountOperationLedger;
import com.showcase.account.application.port.out.AccountStore;
import com.showcase.account.application.port.out.TransactionRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free application core to the adapters that implement its out-ports. The
 * one bean serves all three in-ports (CreateAccountUseCase, AccountOperationUseCase,
 * AccountQueries).
 */
@Configuration
public class AccountApplicationConfig {

    @Bean
    AccountApplicationService accountApplicationService(AccountStore accountStore, AccountOperationLedger ledger,
                                                        TransactionRunner transactions) {
        return new AccountApplicationService(accountStore, ledger, transactions);
    }
}
