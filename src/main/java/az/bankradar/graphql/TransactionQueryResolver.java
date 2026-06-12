package az.bankradar.graphql;

import az.bankradar.alert.AlertHistory;
import az.bankradar.alert.AlertHistoryRepository;
import az.bankradar.transaction.Transaction;
import az.bankradar.transaction.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

/**
 * GraphQL resolver — flexible analyst queries.
 *
 * Test in browser:
 *   http://localhost:8080/graphiql
 *
 * Example query:
 *   query {
 *     recentAlerts { id transactionId customerId riskScore status }
 *   }
 */
@Controller
@RequiredArgsConstructor
public class TransactionQueryResolver {

    private final TransactionRepository transactionRepository;
    private final AlertHistoryRepository alertRepository;

    @QueryMapping
    @PreAuthorize("hasAnyRole('ANALYST', 'MANAGER', 'ADMIN')")
    public Transaction transaction(@Argument String id) {
        return transactionRepository.findById(UUID.fromString(id)).orElse(null);
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('ANALYST', 'MANAGER', 'ADMIN')")
    public List<Transaction> customerTransactions(@Argument String customerId) {
        return transactionRepository.findByCustomerIdOrderByTimestampDesc(customerId);
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('ANALYST', 'MANAGER', 'ADMIN')")
    public List<Transaction> flaggedTransactions() {
        List<Transaction> suspicious = transactionRepository
                .findByRiskStatusOrderByTimestampDesc(Transaction.RiskStatus.SUSPICIOUS);
        List<Transaction> fraud = transactionRepository
                .findByRiskStatusOrderByTimestampDesc(Transaction.RiskStatus.FRAUD);

        return java.util.stream.Stream.concat(fraud.stream(), suspicious.stream()).toList();
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('ANALYST', 'MANAGER', 'ADMIN')")
    public List<AlertHistory> recentAlerts() {
        return alertRepository.findTop50ByOrderByCreatedAtDesc();
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('ANALYST', 'MANAGER', 'ADMIN')")
    public List<AlertHistory> customerAlerts(@Argument String customerId) {
        return alertRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }
}
