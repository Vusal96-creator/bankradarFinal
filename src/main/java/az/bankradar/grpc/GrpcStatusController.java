package az.bankradar.grpc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class GrpcStatusController {

    @Value("${grpc.server.port:9090}")
    private int grpcPort;

    @GetMapping("/api/grpc/status")
    public Map<String, Object> status() {
        return Map.of(
                "status", grpcPort > 0 ? "UP" : "DISABLED",
                "server", grpcPort > 0 ? "localhost:" + grpcPort : "disabled",
                "service", "RiskStreamService",
                "message", "gRPC server daxili risk stream üçün aktivdir."
        );
    }
}