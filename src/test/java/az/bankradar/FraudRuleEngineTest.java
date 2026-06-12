package az.bankradar.risk;

import az.bankradar.transaction.TransactionEvent;
import az.bankradar.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for FraudRuleEngine.
 * Demonstrates JUnit 5 + Mockito patterns.
 */
@ExtendWith(MockitoExtension.class)
class FraudRuleEngineTest {

    @Mock
    private TransactionRepository repository;

    @InjectMocks
    private FraudRuleEngine ruleEngine;

    @BeforeEach
    void setUp() {
        // @Value-injected fields don't get set by Mockito — set them manually
        ReflectionTestUtils.setField(ruleEngine, "velocityThreshold", 5);
        ReflectionTestUtils.setField(ruleEngine, "velocityWindowSeconds", 60);
        ReflectionTestUtils.setField(ruleEngine, "highAmountThreshold", new BigDecimal("10000"));
    }

    @Test
    void normalTransactionInAzerbaijan_lowScore() {
        when(repository.countRecentByCustomer(anyString(), any())).thenReturn(1L);

        TransactionEvent event = baseEvent()
                .amount(new BigDecimal("50"))
                .country("AZ")
                .build();

        FraudRuleEngine.RuleResult result = ruleEngine.evaluate(event);

        assertThat(result.score()).isEqualTo(0);
    }

    @Test
    void highAmountInForeignCountry_combinesToHigh() {
        when(repository.countRecentByCustomer(anyString(), any())).thenReturn(1L);

        TransactionEvent event = baseEvent()
                .amount(new BigDecimal("15000"))   // > 10k → +30
                .country("RU")                     // foreign → +25
                .build();

        FraudRuleEngine.RuleResult result = ruleEngine.evaluate(event);

        assertThat(result.score()).isEqualTo(55);
        assertThat(result.reasons()).contains("high-amount", "geo");
    }

    @Test
    void velocityTriggers_addsScore() {
        when(repository.countRecentByCustomer(anyString(), any())).thenReturn(10L);  // > 5

        TransactionEvent event = baseEvent()
                .amount(new BigDecimal("100"))
                .country("AZ")
                .build();

        FraudRuleEngine.RuleResult result = ruleEngine.evaluate(event);

        assertThat(result.score()).isEqualTo(35);
        assertThat(result.reasons()).contains("velocity:10");
    }

    @Test
    void allThreeRulesTriggered_capsAt100() {
        when(repository.countRecentByCustomer(anyString(), any())).thenReturn(20L);

        TransactionEvent event = baseEvent()
                .amount(new BigDecimal("50000"))
                .country("US")
                .build();

        FraudRuleEngine.RuleResult result = ruleEngine.evaluate(event);

        // 35 + 30 + 25 = 90, well below cap, but verify it's high
        assertThat(result.score()).isEqualTo(90);
        assertThat(result.isFraud(70)).isTrue();
    }

    @Test
    void borderlineDetection_works() {
        FraudRuleEngine.RuleResult borderline = new FraudRuleEngine.RuleResult(55, "x");
        assertThat(borderline.isBorderline(40, 70)).isTrue();

        FraudRuleEngine.RuleResult safe = new FraudRuleEngine.RuleResult(20, "x");
        assertThat(safe.isBorderline(40, 70)).isFalse();

        FraudRuleEngine.RuleResult clearFraud = new FraudRuleEngine.RuleResult(85, "x");
        assertThat(clearFraud.isBorderline(40, 70)).isFalse();
        assertThat(clearFraud.isFraud(70)).isTrue();
    }

    private TransactionEvent.TransactionEventBuilder baseEvent() {
        return TransactionEvent.builder()
                .transactionId(UUID.randomUUID())
                .customerId("CUST-001")
                .currency("AZN")
                .merchantCategory("GROCERY")
                .timestamp(Instant.now());
    }
}
