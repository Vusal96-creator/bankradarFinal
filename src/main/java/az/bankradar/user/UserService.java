package az.bankradar.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AppUser register(String fullName, String username, String password, UserRole role) {
        String cleanUsername = normalize(username);

        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Ad və soyad boş ola bilməz.");
        }
        if (cleanUsername.isBlank()) {
            throw new IllegalArgumentException("İstifadəçi adı boş ola bilməz.");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("Şifrə minimum 6 simvol olmalıdır.");
        }
        if (userRepository.existsByUsername(cleanUsername)) {
            throw new IllegalArgumentException("Bu istifadəçi adı artıq mövcuddur.");
        }

        UserRole finalRole = role == null ? UserRole.ANALYST : role;

        AppUser user = AppUser.builder()
                .fullName(fullName.trim())
                .username(cleanUsername)
                .passwordHash(passwordEncoder.encode(password))
                .role(finalRole)
                .enabled(true)
                .build();

        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public AppUser login(String username, String password) {
        AppUser user = userRepository.findByUsername(normalize(username))
                .orElseThrow(() -> new IllegalArgumentException("Yanlış istifadəçi adı və ya şifrə"));

        if (!user.isEnabled()) {
            throw new IllegalArgumentException("İstifadəçi deaktivdir.");
        }

        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Yanlış istifadəçi adı və ya şifrə");
        }

        return user;
    }

    private String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
