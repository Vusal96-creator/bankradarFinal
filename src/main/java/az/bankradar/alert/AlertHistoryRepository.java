package az.bankradar.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AlertHistoryRepository extends JpaRepository<AlertHistory, UUID> {

    List<AlertHistory> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<AlertHistory> findTop50ByOrderByCreatedAtDesc();
}
