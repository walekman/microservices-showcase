package com.showcase.gateway.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the running build's identity as an "info metric": a gauge fixed at 1 whose labels
 * carry the metadata (Prometheus convention, e.g. prometheus_build_info). Kept off every other
 * series as a common tag on purpose -- a common tag would re-label every series on each deploy.
 * BuildProperties is optional so a run from an IDE without build-info.properties reports
 * "unknown" instead of failing to start.
 */
@Configuration(proxyBeanMethods = false)
public class BuildInfoMetricsConfig {

    @Bean
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
}
