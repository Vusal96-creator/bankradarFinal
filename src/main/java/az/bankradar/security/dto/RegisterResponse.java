package az.bankradar.security.dto;

public record RegisterResponse(
        String message,
        String username,
        String fullName,
        String role
) {
}
