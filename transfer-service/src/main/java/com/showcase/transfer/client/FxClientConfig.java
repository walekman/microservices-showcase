package com.showcase.transfer.client;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// Same shape as FraudClientConfig. The injected builder already carries
// AuthorizationPropagatingInterceptor (ServiceOAuth2ClientConfig's RestClientCustomizer), so the
// live saga's call relays the user's token -- which holds fx-reader through "customer".
@Configuration
public class FxClientConfig {

    @Bean
    public FxClient fxClient(RestClient.Builder builder, FxClientProperties properties) {
        RestClient restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        HttpClientSettings.defaults()
                                .withConnectTimeout(properties.connectTimeout())
                                .withReadTimeout(properties.readTimeout())))
                .build();
        return new FxClient(restClient);
    }
}
