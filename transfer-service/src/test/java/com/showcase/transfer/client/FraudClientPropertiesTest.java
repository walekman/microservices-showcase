package com.showcase.transfer.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FraudClientPropertiesTest {

    @Test
    void defaultsTimeoutsWhenNotConfigured() {
        FraudClientProperties properties = new FraudClientProperties("http://fraud-service:8084", null, null);

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void keepsExplicitTimeouts() {
        FraudClientProperties properties = new FraudClientProperties(
                "http://fraud-service:8084", Duration.ofMillis(500), Duration.ofSeconds(1));

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(1));
    }
}
