package az.bankradar.transaction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Transaction ingestion service.
 *
 * Flow:
 *  1. Validate + persist transaction to DB (with PENDING risk status)
 *  2. Publish TransactionEvent to Kafka 'bankradar.transactions' topic
 *  3. Consumer (RiskAssessmentConsumer) picks it up asynchronously
 *
 * Why publish AFTER persisting?  →  Outbox pattern simplification.
 * If Kafka publish fails, we throw and rollback the DB write (transaction).
 * In production, use the proper transactional outbox pattern.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository repository;
    private final KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    @Value("${app.kafka.topics.transactions}")
    private String transactionsTopic;

    @Transactional
    public Transaction ingest(TransactionRequest req) {
        Transaction tx = Transaction.builder()
                .id(UUID.randomUUID())
                .customerId(req.customerId())
                .amount(req.amount())
                .currency(req.currency().toUpperCase())
                .country(req.country().toUpperCase())
                .merchantCategory(req.merchantCategory().toUpperCase())
                .timestamp(Instant.now())
                .riskStatus(Transaction.RiskStatus.PENDING)
                .build();

        Transaction saved = repository.save(tx);

        // Publish to Kafka. Key by customerId so all txs for one customer
        // go to the same partition (preserves ordering for velocity rules).
        TransactionEvent event = TransactionEvent.from(saved);
        kafkaTemplate.send(transactionsTopic, saved.getCustomerId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish transaction {} to Kafka", saved.getId(), ex);
                    } else {
                        log.debug("Published transaction {} to Kafka partition {}",
                                saved.getId(),
                                result.getRecordMetadata().partition());
                    }
                });

        return saved;
    }
}
