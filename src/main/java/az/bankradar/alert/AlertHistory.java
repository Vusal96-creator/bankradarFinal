package az.bankradar.alert;

import az.bankradar.transaction.Transaction;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * AlertHistory — stores every fraud alert that was sent.
 * Useful for analyst reports and audit trail.
 */
@Entity
@Table(name = "alert_history", indexes = {
    @Index(name = "idx_alert_tx", columnList = "transactionId"),
    @Index(name = "idx_alert_created", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID transactionId;

    @Column(nullable = false)
    private String customerId;

    @Column(nullable = false)
    private Integer riskScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Transaction.RiskStatus status;

    @Column(columnDefinition = "TEXT")
    private String reasons;

    @Column(nullable = false)
    private Instant createdAt;
}
