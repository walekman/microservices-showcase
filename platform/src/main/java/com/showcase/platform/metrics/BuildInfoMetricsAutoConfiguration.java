package com.showcase.platform.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;

/**
 * Metric identity shared by every service.
 *
 * <p>The running build's identity is an "info metric": a gauge fixed at 1 whose labels carry the
 * metadata (Prometheus convention, e.g. prometheus_build_info). Version and commit are kept off
 * every other series on purpose -- as common tags they would re-label every series on each deploy.
 * BuildProperties is optional so a run from an IDE without build-info.properties reports
 * "unknown" instead of failing to start.
 *
 * <p>The service name, which does not change between deploys, is a common tag ({@code application})
 * on every series: the provisioned JVM (Micrometer) dashboard selects and filters by it.
 */
@AutoConfiguration
@ConditionalOnClass(MeterBinder.class)
public class BuildInfoMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "applicationInfoMetrics")
    MeterBinder applicationInfoMetrics(ObjectProvider<BuildProperties> buildProperties,
                                       @Value("${info.commit:unknown}") String commit) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "unknown";
        return registry -> Gauge.builder("application.info", () -> 1)
                .description("Constant 1; the labels carry the running build's version and commit")
                .tag("version", version)
                .tag("commit", commit)
                .strongReference(true)
                .register(registry);
    }

    @Bean
    @ConditionalOnProperty("spring.application.name")
    @ConditionalOnMissingBean(name = "applicationTagMeterFilter")
    MeterFilter applicationTagMeterFilter(@Value("${spring.application.name}") String application) {
        return MeterFilter.commonTags(Tags.of("application", application));
    }
}
