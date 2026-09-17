package com.showcase.fraud;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// See AccountServiceApplication's comment: a plain HTTP-bearer scheme so Swagger UI's
// Authorize button can attach a token obtained via the README's curl instructions.
@OpenAPIDefinition(
        info = @Info(
                title = "Fraud Service API",
                version = "v1",
                description = "Stateless account-blocklist screen for the transfer saga."
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@SpringBootApplication
@ConfigurationPropertiesScan
public class FraudServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudServiceApplication.class, args);
    }
}
