package az.bankradar.ai;

import jakarta.validation.constraints.NotBlank;

public record AiChatRequest(
        @NotBlank(message = "Sual boş ola bilməz")
        String message
) {
}
