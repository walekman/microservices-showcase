package com.showcase.fraud.api;

import com.showcase.fraud.domain.AccountBlockedException;
import com.showcase.platform.web.PlatformExceptionHandler;
import com.showcase.platform.web.Problems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler extends PlatformExceptionHandler {

    @ExceptionHandler(AccountBlockedException.class)
    public ProblemDetail handleBlocked(AccountBlockedException ex) {
        return Problems.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_BLOCKED", "Account blocked", ex.getMessage());
    }
}
