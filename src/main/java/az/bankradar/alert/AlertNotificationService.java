package az.bankradar.alert;

import az.bankradar.grpc.InternalAlertEvent;
import az.bankradar.transaction.Transaction;
import az.bankradar.transaction.TransactionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Sends fraud alerts via:
 *   1. WebSocket (browser dashboards)
 *   2. gRPC stream (other banking microservices — Card Blocker, Compliance...)
 *   3. PostgreSQL alert_history (audit trail)
 *
 * The gRPC fan-out uses a Spring ApplicationEvent so the gRPC layer doesn't
 * depend on this service directly (and new listeners can be added freely).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertNotificationService {

    private final SimpMessagingTemplate messagingTemplate;
    private final AlertHistoryRepository alertRepository;
    private final ApplicationEventPublisher eventPublisher;

    private static final String ALERT_TOPIC = "/topic/alerts";

    @Transactional
    public void sendAlert(TransactionEvent event,
                          int riskScore,
                          Transaction.RiskStatus status,
                          String reasons) {

        AlertMessage message = AlertMessage.builder()
                .transactionId(event.getTransactionId())
                .customerId(event.getCustomerId())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .country(event.getCountry())
                .riskScore(riskScore)
                .status(status)
                .reasons(reasons)
                .timestamp(Instant.now())
                .build();

        // 1. WebSocket broadcast
        messagingTemplate.convertAndSend(ALERT_TOPIC, message);

        // 2. gRPC fan-out (via internal Spring event)
        eventPublisher.publishEvent(new InternalAlertEvent(message));

        // 3. Audit trail
        AlertHistory history = AlertHistory.builder()
                .transactionId(event.getTransactionId())
                .customerId(event.getCustomerId())
                .riskScore(riskScore)
                .status(status)
                .reasons(reasons)
                .createdAt(Instant.now())
                .build();
        alertRepository.save(history);

        log.info("Alert dispatched (WS + gRPC + history): tx={} status={} score={}",
                event.getTransactionId(), status, riskScore);
    }
}
