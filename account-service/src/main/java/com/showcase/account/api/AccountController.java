package com.showcase.account.api;

import com.showcase.account.domain.Account;
import com.showcase.account.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
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

// @Validated turns on method-parameter validation (constraints directly on @RequestHeader/
// @PathVariable params, as opposed to @Valid on a @RequestBody object) -- without it, a
// blank or overlong Idempotency-Key reaches AccountOperation's constructor guard as an
// uncaught IllegalArgumentException, surfacing as a generic 500 instead of a 400.
@RestController
@RequestMapping("/accounts")
@Validated
public class AccountController {

    // Must match transfer-service's own Keycloak client id (the "azp" claim on its
    // client-credentials token) -- see AuthorizationPropagatingInterceptor.SERVICE_REGISTRATION_ID
    // in transfer-service, and docs/phase-7b-account-ownership-authorization.md's Design
    // Decisions for why debit's ownership check exempts this caller.
    private static final String TRUSTED_SERVICE_CLIENT_ID = "transfer-service";

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@AuthenticationPrincipal Jwt jwt,
                                                           @Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(
                UUID.fromString(jwt.getSubject()), request.ownerName(), request.initialBalance());
        return ResponseEntity.created(URI.create("/accounts/" + account.getId()))
                .body(AccountResponse.from(account));
    }

    // account-admin only (see SecurityConfig) -- deliberately unfiltered, unlike every other
    // read in this controller.
    @GetMapping
    public List<AccountResponse> getAllAccounts() {
        return accountService.getAllAccounts().stream()
                .map(AccountResponse::from)
                .toList();
    }

    @GetMapping("/mine")
    public List<AccountResponse> getMyAccounts(@AuthenticationPrincipal Jwt jwt) {
        return accountService.getMyAccounts(UUID.fromString(jwt.getSubject())).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return AccountResponse.from(accountService.getAccount(id, UUID.fromString(jwt.getSubject())));
    }

    // Any authenticated caller, deliberately NOT owner-gated -- the one exception to this
    // controller's ownership rule. Returns only ownerName, never balance or ownerId, so a
    // recipient can be shown as a name instead of a bare UUID without leaking their balance.
    // See docs/phase-9-bank-ui.md's Design Decisions.
    @GetMapping("/{id}/summary")
    public AccountSummaryResponse getAccountSummary(@PathVariable UUID id) {
        return AccountSummaryResponse.from(accountService.getAccountSummary(id));
    }

    // Existence only, no body, no ownership check -- see docs/phase-7b-account-ownership-authorization.md.
    // Deliberately not reused as HEAD /accounts/{id}: HEAD shares getAccount's handler, so it
    // would inherit that endpoint's ownership check instead of staying a general probe.
    @GetMapping("/exists/{id}")
    public ResponseEntity<Void> accountExists(@PathVariable UUID id) {
        accountService.requireAccountExists(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/debit")
    public AccountResponse debit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                  @Valid @RequestBody AmountRequest request,
                                  @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey) {
        UUID callerId = UUID.fromString(jwt.getSubject());
        boolean serviceCaller = TRUSTED_SERVICE_CLIENT_ID.equals(jwt.getClaimAsString("azp"));
        return AccountResponse.from(accountService.debit(id, request.amount(), idempotencyKey, callerId, serviceCaller));
    }

    @PostMapping("/{id}/credit")
    public AccountResponse credit(@PathVariable UUID id, @Valid @RequestBody AmountRequest request,
                                   @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey) {
        return AccountResponse.from(accountService.credit(id, request.amount(), idempotencyKey));
    }
}
