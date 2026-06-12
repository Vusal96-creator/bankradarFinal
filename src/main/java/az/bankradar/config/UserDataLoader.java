package az.bankradar.config;

import az.bankradar.user.UserRepository;
import az.bankradar.user.UserRole;
import az.bankradar.user.UserService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class UserDataLoader implements CommandLineRunner {

    private final UserService userService;
    private final UserRepository userRepository;

    public UserDataLoader(UserService userService, UserRepository userRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) {
        createIfMissing("Leyla Həsənova", "leyla.hasanova", "Analyst123!", UserRole.ANALYST);
        createIfMissing("Kamran Rzayev", "kamran.rzayev", "Analyst123!", UserRole.ANALYST);
        createIfMissing("Farid Məmmədov", "farid.mammadov", "Manager123!", UserRole.MANAGER);
        createIfMissing("Nigar Vəliyeva", "nigar.valiyeva", "Manager123!", UserRole.MANAGER);
        createIfMissing("Nərmin Əliyeva", "narmin.aliyeva", "Admin123!", UserRole.ADMIN);
        createIfMissing("Rəşad Kərimli", "rashad.karimli", "Admin123!", UserRole.ADMIN);
        createIfMissing("System Gateway", "system.gateway", "System123!", UserRole.SYSTEM);
        createIfMissing("Core Processor", "core.processor", "System123!", UserRole.SYSTEM);
    }

    private void createIfMissing(String fullName, String username, String password, UserRole role) {
        if (!userRepository.existsByUsername(username)) {
            userService.register(fullName, username, password, role);
        }
    }
}
