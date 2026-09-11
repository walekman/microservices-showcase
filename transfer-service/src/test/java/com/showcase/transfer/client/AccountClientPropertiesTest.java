package com.showcase.transfer.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AccountClientPropertiesTest {

    @Test
    void appliesDefaultTimeoutsWhenThePropertiesAreAbsent() {
        AccountClientProperties properties =
                new AccountClientProperties("http://account-service:8081", null, null);

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void keepsConfiguredTimeouts() {
        AccountClientProperties properties = new AccountClientProperties(
                "http://account-service:8081", Duration.ofMillis(250), Duration.ofSeconds(30));

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
    }
}
