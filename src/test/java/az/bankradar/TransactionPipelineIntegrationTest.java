package az.bankradar;

import az.bankradar.alert.AlertHistoryRepository;
import az.bankradar.risk.GrokFraudClient;
import az.bankradar.security.JwtService;
import az.bankradar.transaction.Transaction;
import az.bankradar.transaction.TransactionRepository;
import az.bankradar.transaction.TransactionRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class TransactionPipelineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bankradar_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    );

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        registry.add("app.grok.api-key", () -> "test-key-not-used");
        registry.add("app.grok.model", () -> "llama-3.1-8b-instant");

        registry.add("grpc.server.port", () -> "0");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AlertHistoryRepository alertRepository;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private GrokFraudClient grokFraudClient;

    private String authHeader;

    @BeforeEach
    void setUp() {
        when(grokFraudClient.analyse(any(), anyString())).thenReturn(10);

        String token = jwtService.generateToken("integration-test", "SYSTEM");
        authHeader = "Bearer " + token;
    }

    @Test
    @DisplayName("Normal transaction is classified as SAFE")
    void normalTransaction_classifiedSafe() {
        TransactionRequest body = new TransactionRequest(
                "CUST-IT-001",
                new BigDecimal("50"),
                "AZN",
                "AZ",
                "GROCERY"
        );

        Transaction created = postTransaction(body);

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Transaction tx = transactionRepository.findById(created.getId()).orElseThrow();

                    assertThat(tx.getRiskStatus()).isEqualTo(Transaction.RiskStatus.SAFE);
                    assertThat(tx.getRiskScore()).isEqualTo(0);
                });

        assertThat(alertRepository.findByCustomerIdOrderByCreatedAtDesc("CUST-IT-001"))
                .isEmpty();
    }

    @Test
    @DisplayName("Big foreign transaction is classified as SUSPICIOUS and creates alert")
    void bigForeignTransaction_classifiedSuspiciousOrFraud() {
        TransactionRequest body = new TransactionRequest(
                "CUST-IT-002",
                new BigDecimal("15000"),
                "AZN",
                "RU",
                "ELECTRONICS"
        );

        Transaction created = postTransaction(body);

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Transaction tx = transactionRepository.findById(created.getId()).orElseThrow();

                    assertThat(tx.getRiskStatus()).isEqualTo(Transaction.RiskStatus.SUSPICIOUS);
                    assertThat(tx.getRiskScore()).isEqualTo(65);
                });

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(
                        alertRepository.findByCustomerIdOrderByCreatedAtDesc("CUST-IT-002")
                ).isNotEmpty());
    }

    @Test
    @DisplayName("Extreme transaction is classified as FRAUD")
    void extremeTransaction_classifiedFraud() {
        TransactionRequest body = new TransactionRequest(
                "CUST-IT-003",
                new BigDecimal("50000"),
                "AZN",
                "US",
                "ELECTRONICS"
        );

        for (int i = 0; i < 6; i++) {
            postTransaction(body);
        }

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    long fraudCount = transactionRepository
                            .findByCustomerIdOrderByTimestampDesc("CUST-IT-003")
                            .stream()
                            .filter(t -> t.getRiskStatus() == Transaction.RiskStatus.FRAUD)
                            .count();

                    assertThat(fraudCount).isGreaterThan(0);
                });
    }

    private Transaction postTransaction(TransactionRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, authHeader);

        ResponseEntity<Transaction> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/transactions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Transaction.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();

        return response.getBody();
    }
}