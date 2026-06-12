package az.bankradar.risk;

import az.bankradar.transaction.TransactionEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Grok (xAI) LLM API client for AI-powered fraud analysis.
 *
 * Used ONLY for borderline cases (rule score 40–70) — the rule engine
 * handles obvious fraud and obvious safe transactions on its own.
 *
 * Resilience4j Circuit Breaker:
 *   - Configured in application.yml under resilience4j.circuitbreaker.instances.grokFraudClient
 *   - If Grok API fails repeatedly, CB opens and fallback() is called instead
 *
 * Resilience4j Retry:
 *   - Up to 3 attempts with 500ms wait between, in case of transient failures
 *
 * Get your Grok API key at https://console.x.ai/  →  set GROK_API_KEY env var
 */
@Component
@Slf4j
public class GrokFraudClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.grok.api-key}")
    private String apiKey;

    @Value("${app.grok.model}")
    private String model;

    @Value("${app.grok.timeout-ms}")
    private long timeoutMs;

    public GrokFraudClient(@Value("${app.grok.api-url}") String apiUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .build();
    }

    /**
     * Asks Grok to analyse a borderline transaction and return a JSON verdict.
     * Returns an integer adjustment in the range [-30..+30] that is ADDED to the rule score.
     */
    @CircuitBreaker(name = "grokFraudClient", fallbackMethod = "analyseFallback")
    @Retry(name = "grokFraudClient")
    public int analyse(TransactionEvent event, String ruleReasons) {
        log.info("Calling Grok for borderline tx={}", event.getTransactionId());

        String prompt = buildPrompt(event, ruleReasons);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.1,
                "max_tokens", 200
        );

        String response = restClient.post()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return parseAdjustment(response);
    }

    /**
     * Fallback: when CB is open or Grok fails, return 0 (no adjustment).
     * The rule-based score is used as-is.  This is SAFE because rules already
     * caught real fraud (≥70) — we only lose nuance on borderline cases.
     */
    @SuppressWarnings("unused") // called by Resilience4j reflectively
    private int analyseFallback(TransactionEvent event, String ruleReasons, Throwable ex) {
        log.warn("Grok unavailable for tx={}, falling back. Reason: {}",
                event.getTransactionId(), ex.getMessage());
        return 0;
    }

    private String buildPrompt(TransactionEvent event, String ruleReasons) {
        return """
                Analyse this borderline banking transaction for fraud risk.
                Transaction:
                  customer: %s
                  amount: %s %s
                  country: %s
                  merchant category: %s
                  timestamp: %s
                Rule engine flagged reasons: %s

                Respond ONLY with valid JSON of the form:
                {"adjustment": <integer between -30 and 30>, "explanation": "<short reason>"}
                Positive adjustment = more suspicious, negative = looks legitimate.
                """.formatted(
                event.getCustomerId(),
                event.getAmount(),
                event.getCurrency(),
                event.getCountry(),
                event.getMerchantCategory(),
                event.getTimestamp(),
                ruleReasons);
    }

    private int parseAdjustment(String chatCompletionJson) {
        try {
            JsonNode root = objectMapper.readTree(chatCompletionJson);
            String content = root.at("/choices/0/message/content").asText();
            JsonNode verdict = objectMapper.readTree(content);
            int adj = verdict.path("adjustment").asInt(0);
            return Math.max(-30, Math.min(30, adj));   // clamp
        } catch (Exception e) {
            log.warn("Failed to parse Grok response, returning 0", e);
            return 0;
        }
    }

    private static final String SYSTEM_PROMPT =
            "You are a senior banking fraud analyst. Given a borderline transaction, "
            + "estimate how suspicious it really is. Be conservative. "
            + "Respond ONLY with valid JSON, never with explanatory text outside the JSON.";
}
