package com.showcase.account;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@OpenAPIDefinition(
        info = @Info(
                title = "Account Service API",
                version = "v1",
                description = "Owns accounts and balances; debit/credit with optimistic locking."
        )
)
@SpringBootApplication
@ConfigurationPropertiesScan
public class AccountServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
