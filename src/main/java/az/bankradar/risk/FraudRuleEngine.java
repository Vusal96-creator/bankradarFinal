package az.bankradar.risk;

import az.bankradar.transaction.TransactionEvent;
import az.bankradar.transaction.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class FraudRuleEngine {

    private final TransactionRepository repository;

    @Value("${app.fraud.velocity-threshold}")
    private int velocityThreshold;

    @Value("${app.fraud.velocity-window-seconds}")
    private int velocityWindowSeconds;

    @Value("${app.fraud.high-amount-threshold}")
    private BigDecimal highAmountThreshold;

    private static final BigDecimal VERY_HIGH_AMOUNT_THRESHOLD = new BigDecimal("50000");

    public RuleResult evaluate(TransactionEvent event) {
        int score = 0;
        StringBuilder reasons = new StringBuilder();

        Instant since = Instant.now().minus(Duration.ofSeconds(velocityWindowSeconds));
        long recentCount = repository.countRecentByCustomer(event.getCustomerId(), since);

        if (recentCount > velocityThreshold) {
            score += 35;
            reasons.append("velocity:")
                    .append(recentCount)
                    .append(";");
        }

        if (event.getAmount() != null) {
            if (event.getAmount().compareTo(VERY_HIGH_AMOUNT_THRESHOLD) >= 0) {
                score += 50;
                reasons.append("very-high-amount:")
                        .append(event.getAmount())
                        .append(";");
            } else if (event.getAmount().compareTo(highAmountThreshold) > 0) {
                score += 30;
                reasons.append("high-amount:")
                        .append(event.getAmount())
                        .append(";");
            }
        }

        String country = event.getCountry();

        if (country != null && !"AZ".equalsIgnoreCase(country)) {
            score += 25;
            reasons.append("geo:")
                    .append(country)
                    .append(";");
        }

        score = Math.min(score, 100);

        log.debug(
                "Rule eval — tx={} customer={} score={} reasons={}",
                event.getTransactionId(),
                event.getCustomerId(),
                score,
                reasons
        );

        return new RuleResult(score, reasons.toString());
    }

    public record RuleResult(int score, String reasons) {

        public boolean isBorderline(int minThreshold, int maxThreshold) {
            return score >= minThreshold && score < maxThreshold;
        }

        public boolean isFraud(int threshold) {
            return score >= threshold;
        }
    }
}