package com.showcase.account;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Lets Swagger UI's Authorize button attach a bearer token to every "Try it out" call --
// paste in a token obtained per the README's "Authentication (Keycloak)" section. This is
// deliberately just an HTTP-bearer scheme, not a full OAuth2 password-grant flow wired into
// the Authorize dialog itself (that would need the client secret/flow exposed to the browser
// for no real benefit here, since getting a token via curl already works).
@OpenAPIDefinition(
        info = @Info(
                title = "Account Service API",
                version = "v1",
                description = "Owns accounts and balances; debit/credit with optimistic locking."
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@SpringBootApplication
@ConfigurationPropertiesScan
public class AccountServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
