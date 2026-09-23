package com.showcase.transfer.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties(null);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    // Manually constructing this bean (rather than letting Boot's own Kafka autoconfiguration
    // build it) means spring.kafka.template.observation-enabled (application.yml, Phase 8
    // Task 2) is silently a no-op for it -- that property only customizes Boot's OWN
    // autoconfigured KafkaTemplate bean, which backs off entirely once this bean exists.
    // Found live during Phase 8 Task 7: the producer sent zero tracing headers at all (not a
    // wrong header name, literally none) until observation was wired in explicitly here.
    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory,
                                                         ObservationRegistry observationRegistry) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        template.setObservationRegistry(observationRegistry);
        template.setObservationEnabled(true);
        return template;
    }
}
