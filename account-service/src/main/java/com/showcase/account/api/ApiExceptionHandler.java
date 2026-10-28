package com.showcase.account.api;

import com.showcase.account.domain.AccountAlreadyExistsException;
import com.showcase.account.domain.AccountNotFoundException;
import com.showcase.account.domain.AccountOperationConflictException;
import com.showcase.account.domain.CurrencyMismatchException;
import com.showcase.account.domain.InsufficientFundsException;
import com.showcase.platform.web.PlatformExceptionHandler;
import com.showcase.platform.web.Problems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler extends PlatformExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleNotFound(AccountNotFoundException ex) {
        return Problems.of(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found", ex.getMessage());
    }

    @ExceptionHandler(AccountAlreadyExistsException.class)
    public ProblemDetail handleAlreadyExists(AccountAlreadyExistsException ex) {
        return Problems.of(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS", "Account already exists", ex.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
        return Problems.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Insufficient funds", ex.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex) {
        return Problems.of(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH", "Currency mismatch", ex.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleConflict(ObjectOptimisticLockingFailureException ex) {
        return Problems.of(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "Concurrent modification",
                "Account was modified concurrently, please retry");
    }

    @ExceptionHandler(AccountOperationConflictException.class)
    public ProblemDetail handleIdempotencyConflict(AccountOperationConflictException ex) {
        return Problems.of(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", "Idempotency key conflict", ex.getMessage());
    }
}
