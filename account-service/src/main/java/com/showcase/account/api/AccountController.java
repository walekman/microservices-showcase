package com.showcase.account.api;

import com.showcase.account.domain.Account;
import com.showcase.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(request.ownerName(), request.initialBalance());
        return ResponseEntity.created(URI.create("/accounts/" + account.getId()))
                .body(AccountResponse.from(account));
    }

    @GetMapping
    public List<AccountResponse> getAllAccounts() {
        return accountService.getAllAccounts().stream()
                .map(AccountResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable UUID id) {
        return AccountResponse.from(accountService.getAccount(id));
    }

    @PostMapping("/{id}/debit")
    public AccountResponse debit(@PathVariable UUID id, @Valid @RequestBody AmountRequest request,
                                  @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return AccountResponse.from(accountService.debit(id, request.amount(), idempotencyKey));
    }

    @PostMapping("/{id}/credit")
    public AccountResponse credit(@PathVariable UUID id, @Valid @RequestBody AmountRequest request,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return AccountResponse.from(accountService.credit(id, request.amount(), idempotencyKey));
    }
}
