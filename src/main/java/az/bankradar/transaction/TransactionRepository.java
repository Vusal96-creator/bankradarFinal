package az.bankradar.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    List<Transaction> findByCustomerIdOrderByTimestampDesc(String customerId);

    /**
     * Count transactions in the last N seconds for a customer.
     * Used by the velocity rule (e.g., > 5 tx/min = suspicious).
     */
    @Query("SELECT COUNT(t) FROM Transaction t " +
           "WHERE t.customerId = :customerId AND t.timestamp >= :since")
    long countRecentByCustomer(@Param("customerId") String customerId,
                               @Param("since") Instant since);

    List<Transaction> findByRiskStatusOrderByTimestampDesc(Transaction.RiskStatus status);
}
