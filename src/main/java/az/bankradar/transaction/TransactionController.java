package az.bankradar.transaction;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API for transaction ingestion and lookup.
 *
 * POST /api/transactions          — ingest a new transaction (sends to Kafka)
 * GET  /api/transactions/{id}     — fetch a single transaction
 * GET  /api/transactions/customer/{customerId}  — fetch a customer's history
 *
 * Auth: requires ANALYST, MANAGER, ADMIN, or SYSTEM role (see SecurityConfig).
 */
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;
    private final TransactionRepository repository;

    @PostMapping
    public ResponseEntity<Transaction> ingest(@Valid @RequestBody TransactionRequest req) {
        Transaction tx = transactionService.ingest(req);
        return ResponseEntity.accepted().body(tx);  // 202 — being processed async
    }

    @GetMapping("/{id}")
    public ResponseEntity<Transaction> getById(@PathVariable UUID id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/customer/{customerId}")
    public List<Transaction> getByCustomer(@PathVariable String customerId) {
        return repository.findByCustomerIdOrderByTimestampDesc(customerId);
    }
}
