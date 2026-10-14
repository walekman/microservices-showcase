package com.showcase.fraud;

import com.showcase.fraud.service.FraudCheckService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the datasource-micrometer wiring: Boot does not trace JDBC, and the library is pinned
 * outside Boot's BOM, so a version bump that silently stops its auto-configuration would only
 * show up as missing spans in Tempo. Records every observation that stops during a fraud check.
 */
@Testcontainers
@SpringBootTest
class DatabaseTracingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final List<Observation.Context> STOPPED = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class RecordingHandlerConfig {
        @Bean
        ObservationHandler<Observation.Context> recordingHandler() {
            return new ObservationHandler<>() {
                @Override
                public void onStop(Observation.Context context) {
                    STOPPED.add(context);
                }

                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }
            };
        }
    }

    @Autowired
    private FraudCheckService fraudCheckService;

    @Test
    void aFraudCheckTracesItsConnectionAndQueryButNotTheResultSet() {
        STOPPED.clear();

        fraudCheckService.check(UUID.randomUUID());

        List<String> names = STOPPED.stream().map(Observation.Context::getName).toList();
        assertThat(names).contains("jdbc.connection", "jdbc.query").doesNotContain("jdbc.result-set");
        assertThat(STOPPED)
                .filteredOn(context -> context.getName().equals("jdbc.query"))
                .anySatisfy(query -> assertThat(query.getHighCardinalityKeyValues().stream()
                        .map(keyValue -> keyValue.getValue()))
                        .anySatisfy(value -> assertThat(value).containsIgnoringCase("blocked_accounts")));
    }
}
