package com.showcase.fx.api;

import com.showcase.fx.service.RateUnavailableException;
import com.showcase.fx.service.UnsupportedCurrencyException;
import com.showcase.platform.web.PlatformExceptionHandler;
import com.showcase.platform.web.Problems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler extends PlatformExceptionHandler {

    @ExceptionHandler(UnsupportedCurrencyException.class)
    public ProblemDetail handleUnsupportedCurrency(UnsupportedCurrencyException ex) {
        return Problems.of(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY", "Unsupported currency", ex.getMessage());
    }

    @ExceptionHandler(RateUnavailableException.class)
    public ProblemDetail handleRateUnavailable(RateUnavailableException ex) {
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "FX_RATE_UNAVAILABLE", "FX rate unavailable",
                "No exchange rate is available right now");
    }
}
