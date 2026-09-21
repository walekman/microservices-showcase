package com.showcase.notification;

import com.jayway.jsonpath.JsonPath;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the version channels this phase adds. Asserts the behaviour itself, not just that the
 * context starts: /actuator/info must be reachable with no token and must report the build's
 * version. Real HTTP round trip (RANDOM_PORT) so the real SecurityConfig is in the path.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "info.commit=abc1234")
class BuildInfoIT {

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private BuildProperties buildProperties;
    @Autowired
    private Resource otelResource;

    @Test
    void infoEndpointNeedsNoTokenAndReportsTheBuildVersionAndCommit() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/info", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(buildProperties.getVersion()).isNotBlank();
        assertThat((String) JsonPath.read(response.getBody(), "$.build.version"))
                .isEqualTo(buildProperties.getVersion());
        assertThat((String) JsonPath.read(response.getBody(), "$.commit")).isEqualTo("abc1234");
    }

    @Test
    void applicationInfoGaugeIsScrapedWithVersionAndCommitLabels() {
        String scrape = restTemplate.getForObject("/actuator/prometheus", String.class);

        // Labels, not the value: the sample renders as "1", not "1.0", for an *_info metric.
        assertThat(scrape.lines().filter(line -> line.startsWith("application_info{")))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains("version=\"" + buildProperties.getVersion() + "\"")
                        .contains("commit=\"abc1234\""));
    }

    @Test
    void openTelemetryResourceCarriesServiceVersion() {
        assertThat(otelResource.getAttribute(AttributeKey.stringKey("service.version")))
                .isEqualTo(buildProperties.getVersion());
    }
}
