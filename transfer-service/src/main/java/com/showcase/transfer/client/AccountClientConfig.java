package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AccountClientConfig {

    @Bean
    public AccountClient accountClient(RestClient.Builder builder,
                                       AccountClientProperties properties,
                                       ObjectMapper objectMapper) {
        RestClient restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(properties.connectTimeout())
                                .withReadTimeout(properties.readTimeout())))
                .build();
        return new AccountClient(restClient, objectMapper);
    }
}
