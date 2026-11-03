package com.showcase.fx.config;

import com.showcase.fx.service.FxProperties;
import com.showcase.fx.service.RateProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// Same RestClient.Builder + explicit timeouts shape as transfer-service's FraudClientConfig. The
// injected builder carries Boot's observation instrumentation, so each provider call is a span.
@Configuration
public class ProviderClientConfig {

    @Bean
    public RateProvider rateProvider(RestClient.Builder builder, FxProperties properties) {
        RestClient restClient = builder
                .baseUrl(properties.provider().baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        HttpClientSettings.defaults()
                                .withConnectTimeout(properties.provider().connectTimeout())
                                .withReadTimeout(properties.provider().readTimeout())))
                .build();
        return new RateProvider(restClient, properties.supportedCurrencies());
    }
}
