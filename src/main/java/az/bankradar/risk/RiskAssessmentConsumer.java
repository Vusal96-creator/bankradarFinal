package az.bankradar.risk;

import az.bankradar.alert.AlertNotificationService;
import az.bankradar.transaction.Transaction;
import az.bankradar.transaction.TransactionEvent;
import az.bankradar.transaction.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class RiskAssessmentConsumer {

    private final FraudRuleEngine ruleEngine;
    private final GrokFraudClient grokClient;
    private final TransactionRepository repository;
    private final AlertNotificationService alertService;
    private final StringRedisTemplate redis;

    @Value("${app.fraud.llm-borderline-min}")
    private int llmMin;

    @Value("${app.fraud.llm-borderline-max}")
    private int llmMax;

    private static final int FRAUD_THRESHOLD = 70;
    private static final int SUSPICIOUS_THRESHOLD = 40;

    @KafkaListener(
            topics = "${app.kafka.topics.transactions}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consume(TransactionEvent event,
                        @Header(KafkaHeaders.OFFSET) long offset) {

        UUID transactionId = event.getTransactionId();
        String dedupKey = "tx:processed:" + transactionId;

        Boolean firstSeen = redis.opsForValue().setIfAbsent(
                dedupKey,
                "1",
                Duration.ofHours(24)
        );

        if (!Boolean.TRUE.equals(firstSeen)) {
            log.debug("Skipping already processed tx={}", transactionId);
            return;
        }

        log.info("Processing tx={} at offset={}", transactionId, offset);

        FraudRuleEngine.RuleResult result = ruleEngine.evaluate(event);

        int calculatedScore = result.score();

        if (result.isBorderline(llmMin, llmMax)) {
            int adjustment = grokClient.analyse(event, result.reasons());

            calculatedScore = Math.max(
                    0,
                    Math.min(100, calculatedScore + adjustment)
            );

            log.info(
                    "LLM adjusted tx={} score: {} -> {}",
                    transactionId,
                    result.score(),
                    calculatedScore
            );
        }

        final int scoreToSave = calculatedScore;
        final Transaction.RiskStatus statusToSave = classify(scoreToSave);
        final String reasonText = result.reasons();

        repository.findById(transactionId).ifPresentOrElse(
                tx -> {
                    tx.setRiskScore(scoreToSave);
                    tx.setRiskStatus(statusToSave);
                    repository.save(tx);

                    log.info(
                            "Transaction tx={} updated with score={} status={}",
                            transactionId,
                            scoreToSave,
                            statusToSave
                    );
                },
                () -> log.warn("Transaction tx={} not found in database", transactionId)
        );

        if (statusToSave == Transaction.RiskStatus.SUSPICIOUS
                || statusToSave == Transaction.RiskStatus.FRAUD) {

            alertService.sendAlert(
                    event,
                    scoreToSave,
                    statusToSave,
                    reasonText
            );

            log.info(
                    "Alert sent for tx={} score={} status={}",
                    transactionId,
                    scoreToSave,
                    statusToSave
            );
        }
    }

    private Transaction.RiskStatus classify(int score) {
        if (score >= FRAUD_THRESHOLD) {
            return Transaction.RiskStatus.FRAUD;
        }

        if (score >= SUSPICIOUS_THRESHOLD) {
            return Transaction.RiskStatus.SUSPICIOUS;
        }

        return Transaction.RiskStatus.SAFE;
    }
}