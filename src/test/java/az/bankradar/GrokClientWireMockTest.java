package az.bankradar;

import az.bankradar.risk.GrokFraudClient;
import az.bankradar.transaction.TransactionEvent;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class GrokClientWireMockTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    );

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static WireMockServer wireMock;

    static {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        registry.add("app.grok.api-url", () -> wireMock.baseUrl() + "/v1/chat/completions");
        registry.add("app.grok.api-key", () -> "test-key");
        registry.add("app.grok.model", () -> "llama-3.1-8b-instant");

        registry.add("grpc.server.port", () -> "0");
    }

    @Autowired
    private GrokFraudClient grokClient;

    @Autowired(required = false)
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetMocks() {
        wireMock.resetAll();

        if (circuitBreakerRegistry != null) {
            circuitBreakerRegistry.circuitBreaker("grokFraudClient").reset();
        }
    }

    @AfterEach
    void afterEach() {
        wireMock.resetAll();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    void successfulCall_returnsParsedAdjustment() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "choices": [
                                    {
                                      "message": {
                                        "content": "{\\"adjustment\\":20,\\"explanation\\":\\"unusual pattern\\"}"
                                      }
                                    }
                                  ]
                                }
                                """)));

        int adjustment = grokClient.analyse(sampleEvent(), "high-amount;geo");

        assertThat(adjustment).isEqualTo(20);

        wireMock.verify(postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void grokTimeout_circuitBreakerFallbackReturnsZero() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody("Service Unavailable")));

        for (int i = 0; i < 5; i++) {
            int result = grokClient.analyse(sampleEvent(), "test");
            assertThat(result).isEqualTo(0);
        }
    }

    @Test
    void malformedJsonResponse_returnsZeroSafely() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"this\":\"is not what we expected\"}")));

        int adjustment = grokClient.analyse(sampleEvent(), "test");

        assertThat(adjustment).isEqualTo(0);
    }

    private TransactionEvent sampleEvent() {
        return TransactionEvent.builder()
                .transactionId(UUID.randomUUID())
                .customerId("CUST-WM-001")
                .amount(new BigDecimal("15000"))
                .currency("AZN")
                .country("RU")
                .merchantCategory("ELECTRONICS")
                .timestamp(Instant.now())
                .build();
    }
}