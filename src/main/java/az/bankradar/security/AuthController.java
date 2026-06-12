package az.bankradar.security;

import az.bankradar.security.dto.LoginRequest;
import az.bankradar.security.dto.LoginResponse;
import az.bankradar.security.dto.RegisterRequest;
import az.bankradar.security.dto.RegisterResponse;
import az.bankradar.user.AppUser;
import az.bankradar.user.UserRole;
import az.bankradar.user.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtService jwtService;
    private final UserService userService;

    public AuthController(JwtService jwtService, UserService userService) {
        this.jwtService = jwtService;
        this.userService = userService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            AppUser user = userService.login(request.username(), request.password());
            String token = jwtService.generateToken(user.getUsername(), user.getRole().name());

            return ResponseEntity.ok(new LoginResponse(
                    token,
                    user.getUsername(),
                    user.getFullName(),
                    user.getRole().name()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        try {
            UserRole role = request.role() == null ? UserRole.ANALYST : request.role();
            AppUser user = userService.register(
                    request.fullName(),
                    request.username(),
                    request.password(),
                    role
            );

            return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(
                    "İstifadəçi uğurla yaradıldı.",
                    user.getUsername(),
                    user.getFullName(),
                    user.getRole().name()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }
}
