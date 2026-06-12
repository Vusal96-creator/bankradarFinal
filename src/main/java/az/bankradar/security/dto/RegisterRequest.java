package az.bankradar.security.dto;

import az.bankradar.user.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Ad və soyad boş ola bilməz")
        String fullName,

        @NotBlank(message = "İstifadəçi adı boş ola bilməz")
        String username,

        @Size(min = 6, message = "Şifrə minimum 6 simvol olmalıdır")
        String password,

        UserRole role
) {
}
