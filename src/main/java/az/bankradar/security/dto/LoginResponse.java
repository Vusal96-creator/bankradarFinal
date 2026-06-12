package az.bankradar.security.dto;

public record LoginResponse(
        String token,
        String username,
        String fullName,
        String role
) {
}
