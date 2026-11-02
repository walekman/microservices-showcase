package com.showcase.transfer.client;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
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
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        HttpClientSettings.defaults()
                                .withConnectTimeout(properties.connectTimeout())
                                .withReadTimeout(properties.readTimeout())))
                .build();
        return new AccountClient(restClient, objectMapper);
    }
}
