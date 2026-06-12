package az.bankradar.alert;

import az.bankradar.transaction.Transaction;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The payload sent to bank staff over WebSocket when a suspicious or fraud event arrives.
 */
@Data
@Builder
public class AlertMessage {
    private UUID transactionId;
    private String customerId;
    private BigDecimal amount;
    private String currency;
    private String country;
    private Integer riskScore;
    private Transaction.RiskStatus status;
    private String reasons;
    private Instant timestamp;
}
