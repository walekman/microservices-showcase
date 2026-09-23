package com.showcase.transfer.config;

import com.showcase.transfer.service.OutboxPublisherProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

// Declares the outbox topics explicitly rather than relying on the broker's auto-create
// defaults, so the topic layout lives in code and survives a broker with
// auto.create.topics.enable=false. Boot's autoconfigured KafkaAdmin creates them at startup
// and leaves an existing topic with the same settings alone.
//
// One partition, one replica: Compose runs a single broker, so a higher replication factor
// cannot be satisfied, and one partition matches what auto-create produced before. The record
// key is the transfer id, so raising the partition count later keeps per-transfer ordering.
@Configuration
public class KafkaTopicConfig {

    private static final int PARTITIONS = 1;
    private static final int REPLICAS = 1;

    @Bean
    public KafkaAdmin.NewTopics outboxTopics(OutboxPublisherProperties properties) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(properties.topics().completed()).partitions(PARTITIONS).replicas(REPLICAS).build(),
                TopicBuilder.name(properties.topics().failed()).partitions(PARTITIONS).replicas(REPLICAS).build());
    }
}
