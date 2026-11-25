package com.showcase.platform.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class BuildInfoMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BuildInfoMetricsAutoConfiguration.class));

    @Test
    void labelsTheGaugeWithVersionAndCommit() {
        Properties build = new Properties();
        build.setProperty("version", "1.2.3");
        runner.withBean(BuildProperties.class, () -> new BuildProperties(build))
                .withPropertyValues("info.commit=abc1234")
                .run(context -> {
                    Gauge gauge = register(context.getBean("applicationInfoMetrics", MeterBinder.class));
                    assertThat(gauge.getId().getTag("version")).isEqualTo("1.2.3");
                    assertThat(gauge.getId().getTag("commit")).isEqualTo("abc1234");
                    assertThat(gauge.value()).isEqualTo(1.0);
                });
    }

    @Test
    void reportsUnknownWithoutBuildInfo() {
        runner.run(context -> {
            Gauge gauge = register(context.getBean("applicationInfoMetrics", MeterBinder.class));
            assertThat(gauge.getId().getTag("version")).isEqualTo("unknown");
            assertThat(gauge.getId().getTag("commit")).isEqualTo("unknown");
        });
    }

    @Test
    void tagsEveryMeterWithTheApplicationName() {
        runner.withPropertyValues("spring.application.name=transfer-service").run(context -> {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            registry.config().meterFilter(context.getBean("applicationTagMeterFilter", MeterFilter.class));
            Counter counter = registry.counter("transfers.completed");
            assertThat(counter.getId().getTag("application")).isEqualTo("transfer-service");
        });
    }

    @Test
    void addsNoApplicationTagWithoutAnApplicationName() {
        runner.run(context -> assertThat(context).doesNotHaveBean("applicationTagMeterFilter"));
    }

    private static Gauge register(MeterBinder binder) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        binder.bindTo(registry);
        return registry.get("application.info").gauge();
    }
}
