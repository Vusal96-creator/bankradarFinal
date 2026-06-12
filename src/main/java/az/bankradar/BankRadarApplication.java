package az.bankradar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * BankRadar — Real-Time Banking Transaction Fraud Detection System
 *
 * Architecture:
 *   - REST API for transaction ingestion
 *   - Kafka producer/consumer for event-driven processing
 *   - Rule-based fraud engine (fast-path)
 *   - Grok LLM API for AI-powered fraud analysis (slow-path)
 *   - Resilience4j Circuit Breaker protecting LLM calls
 *   - WebSocket for real-time alerts to bank staff
 *   - GraphQL for analyst queries
 */
@SpringBootApplication
@EnableKafka
@EnableAsync
public class BankRadarApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankRadarApplication.class, args);
    }
}
