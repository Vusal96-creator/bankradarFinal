package az.bankradar.transaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka event payload published when a transaction is ingested.
 * Consumed by RiskAssessmentConsumer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionEvent {
    private UUID transactionId;
    private String customerId;
    private BigDecimal amount;
    private String currency;
    private String country;
    private String merchantCategory;
    private Instant timestamp;

    public static TransactionEvent from(Transaction tx) {
        return TransactionEvent.builder()
                .transactionId(tx.getId())
                .customerId(tx.getCustomerId())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .country(tx.getCountry())
                .merchantCategory(tx.getMerchantCategory())
                .timestamp(tx.getTimestamp())
                .build();
    }
}
