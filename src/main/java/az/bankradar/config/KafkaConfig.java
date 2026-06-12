package az.bankradar.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Creates Kafka topics on application startup.
 *
 * - bankradar.transactions: main topic for transaction events
 * - bankradar.transactions.dlq: dead-letter queue for malformed/failed events
 */
@Configuration
public class KafkaConfig {

    @Value("${app.kafka.topics.transactions}")
    private String transactionsTopic;

    @Value("${app.kafka.topics.transactions-dlq}")
    private String dlqTopic;

    @Bean
    public NewTopic transactionsTopic() {
        return TopicBuilder.name(transactionsTopic)
                .partitions(3)        // 3 partitions for parallel consumer scaling
                .replicas(1)          // 1 replica (single broker setup)
                .build();
    }

    @Bean
    public NewTopic transactionsDlqTopic() {
        return TopicBuilder.name(dlqTopic)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
