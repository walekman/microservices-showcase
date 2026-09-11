package com.showcase.transfer.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "account-service")
public record AccountClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    // Boot's binder skips a null Duration silently, which would leave the RestClient with
    // NO timeout at all -- restoring exactly the unbounded-block failure these properties
    // exist to prevent, with no startup error to warn anyone. Default rather than trust
    // every future profile and SPRING_APPLICATION_JSON override to set them.
    public AccountClientProperties {
        connectTimeout = (connectTimeout != null) ? connectTimeout : Duration.ofSeconds(2);
        readTimeout = (readTimeout != null) ? readTimeout : Duration.ofSeconds(5);
    }
}
