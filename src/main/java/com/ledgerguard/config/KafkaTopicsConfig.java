package com.ledgerguard.config;

import com.ledgerguard.outbox.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Declares the ledger event topics and turns on the scheduler the outbox
 * publisher runs from.
 *
 * <p>Topics are declared rather than relying on broker auto-creation, so
 * partition count and replication factor are a deliberate choice that lives in
 * version control instead of whatever the broker defaults happen to be.
 *
 * <p>One partition and one replica suit a single-node development broker. A
 * real deployment would raise both; the numbers are here to be changed, and
 * changing partitions later is disruptive precisely because it changes which
 * partition a key lands on.
 */
@Configuration
@EnableScheduling
public class KafkaTopicsConfig {

    @Bean
    public NewTopic paymentsTopic() {
        return TopicBuilder.name(Topics.PAYMENTS).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic refundsTopic() {
        return TopicBuilder.name(Topics.REFUNDS).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic reversalsTopic() {
        return TopicBuilder.name(Topics.REVERSALS).partitions(1).replicas(1).build();
    }
}
