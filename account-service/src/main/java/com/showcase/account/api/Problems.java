package com.showcase.account.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

/**
 * Builds RFC 7807 problem responses. The {@code type} URI is an identifier, not a
 * dereferenceable URL; the {@code code} property is the stable, machine-readable
 * discriminator that clients (notably Transfer Service) switch on.
 */
final class Problems {

    private static final URI TYPE_BASE = URI.create("https://showcase.example/errors/");

    private Problems() {
    }

    static ProblemDetail of(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(TYPE_BASE.resolve(code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
