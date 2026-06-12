package az.bankradar.security.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "İstifadəçi adı boş ola bilməz")
        String username,

        @NotBlank(message = "Şifrə boş ola bilməz")
        String password
) {
}
