package com.showcase.account.service;

import com.showcase.account.domain.Account;
import com.showcase.account.domain.AccountNotFoundException;
import com.showcase.account.domain.AccountRepository;
import com.showcase.account.domain.InsufficientFundsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository);
    }

    @Test
    void createAccountSavesANewAccount() {
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Account result = accountService.createAccount("Ada Lovelace", new BigDecimal("100.00"));

        assertThat(result.getOwnerName()).isEqualTo("Ada Lovelace");
        assertThat(result.getBalance()).isEqualByComparingTo("100.00");
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    void getAllAccountsReturnsEveryAccount() {
        Account first = new Account("Ada Lovelace", new BigDecimal("100.00"));
        Account second = new Account("Alan Turing", new BigDecimal("50.00"));
        when(accountRepository.findAll()).thenReturn(List.of(first, second));

        List<Account> result = accountService.getAllAccounts();

        assertThat(result).containsExactly(first, second);
    }

    @Test
    void getAccountThrowsWhenAccountIsMissing() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(id))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void debitReducesBalanceAndSaves() {
        UUID id = UUID.randomUUID();
        Account account = new Account("Ada Lovelace", new BigDecimal("100.00"));
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        Account result = accountService.debit(id, new BigDecimal("40.00"));

        assertThat(result.getBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void debitThrowsWhenFundsAreInsufficientAndDoesNotSave() {
        UUID id = UUID.randomUUID();
        Account account = new Account("Ada Lovelace", new BigDecimal("10.00"));
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.debit(id, new BigDecimal("40.00")))
                .isInstanceOf(InsufficientFundsException.class);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void creditIncreasesBalanceAndSaves() {
        UUID id = UUID.randomUUID();
        Account account = new Account("Ada Lovelace", new BigDecimal("100.00"));
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        Account result = accountService.credit(id, new BigDecimal("25.00"));

        assertThat(result.getBalance()).isEqualByComparingTo("125.00");
    }
}
