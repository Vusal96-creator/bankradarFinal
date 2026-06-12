package az.bankradar.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

@Service
public class AiChatService {

    private final RestClient restClient;
    private final String apiUrl;
    private final String apiKey;
    private final String model;

    public AiChatService(
            @Value("${app.grok.api-url}") String apiUrl,
            @Value("${app.grok.api-key:}") String apiKey,
            @Value("${app.grok.model}") String model
    ) {
        this.restClient = RestClient.create();
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public String ask(String userMessage) {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals("your-groq-key-here")) {
            return "AI API açarı backend environment variables daxilində təyin edilməyib. GROK_API_KEY əlavə edin.";
        }

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of(
                                "role", "system",
                                "content", "Sən BankRadar layihəsinin bank fraud və risk analizi üzrə AI köməkçisisən. Cavabları Azərbaycan dilində, qısa və praktik ver."
                        ),
                        Map.of(
                                "role", "user",
                                "content", userMessage
                        )
                ),
                "temperature", 0.3,
                "max_tokens", 500
        );

        try {
            Map<?, ?> response = restClient.post()
                    .uri(apiUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return "AI cavabı boş gəldi.";
            }

            Object choicesObj = response.get("choices");
            if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
                return "AI cavabında choices sahəsi yoxdur.";
            }

            Object firstObj = choices.get(0);
            if (!(firstObj instanceof Map<?, ?> first)) {
                return "AI cavab formatı düzgün deyil.";
            }

            Object messageObj = first.get("message");
            if (!(messageObj instanceof Map<?, ?> message)) {
                return "AI message formatı düzgün deyil.";
            }

            Object content = message.get("content");
            return content == null ? "AI cavabında content yoxdur." : String.valueOf(content);
        } catch (RestClientResponseException ex) {
            return "Groq API xətası: HTTP " + ex.getStatusCode().value() + " — " + ex.getResponseBodyAsString();
        } catch (Exception ex) {
            return "Backend AI xətası: " + ex.getClass().getSimpleName() + " — " + ex.getMessage();
        }
    }
}
