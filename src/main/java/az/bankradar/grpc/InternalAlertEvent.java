package az.bankradar.grpc;

import az.bankradar.alert.AlertMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Internal Spring ApplicationEvent fired when a fraud alert is generated.
 *
 * Subscribers:
 *   - AlertNotificationService — sends WebSocket message and persists history
 *   - RiskStreamGrpcService    — broadcasts to subscribed gRPC clients
 *
 * Using a Spring event means new listeners can be added without changing
 * the producer (e.g., we could add an email listener, Kafka re-publisher, etc.).
 */
@Getter
@RequiredArgsConstructor
public class InternalAlertEvent {
    private final AlertMessage message;
}
