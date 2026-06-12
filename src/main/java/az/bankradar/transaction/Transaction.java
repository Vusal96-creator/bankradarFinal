package az.bankradar.transaction;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transaction entity — represents a single bank transaction.
 * Persisted to PostgreSQL, also serialized to Kafka events.
 */
@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_tx_customer", columnList = "customerId"),
    @Index(name = "idx_tx_timestamp", columnList = "timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private String customerId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String country;          // ISO country code, e.g. "AZ", "TR"

    @Column(nullable = false)
    private String merchantCategory; // e.g. "GROCERY", "ELECTRONICS"

    @Column(nullable = false)
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private RiskStatus riskStatus = RiskStatus.PENDING;

    private Integer riskScore;       // 0..100, null until risk module processes it

    @Version
    private Long version;            // optimistic locking

    public enum RiskStatus {
        PENDING,        // just ingested, not yet assessed
        SAFE,           // risk score < threshold
        SUSPICIOUS,     // borderline, may need LLM analysis
        FRAUD           // confirmed fraud, alert sent
    }
}
