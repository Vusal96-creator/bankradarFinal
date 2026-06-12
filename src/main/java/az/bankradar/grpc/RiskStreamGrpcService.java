package az.bankradar.grpc;

import az.bankradar.alert.AlertMessage;
import az.bankradar.grpc.proto.GetRiskRequest;
import az.bankradar.grpc.proto.RiskEventProto;
import az.bankradar.grpc.proto.RiskStatusProto;
import az.bankradar.grpc.proto.RiskStreamServiceGrpc;
import az.bankradar.grpc.proto.SubscribeRequest;
import az.bankradar.transaction.Transaction;
import az.bankradar.transaction.TransactionRepository;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.context.event.EventListener;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * gRPC server implementing the RiskStreamService defined in risk_stream.proto.
 *
 * Two RPCs:
 *
 *  - SubscribeToRiskEvents: server-side STREAMING.
 *      A bank microservice (e.g., Card Blocker, Compliance Reporter) opens
 *      one long-lived connection and receives every alert event as it happens.
 *      Latency is sub-millisecond on local network — much faster than HTTP
 *      polling or message queue consumers.
 *
 *  - GetRiskAssessment: unary request/response.
 *      Look up the current risk classification for a specific transaction ID.
 *
 * Test from the command line:
 *   grpcurl -plaintext -d '{"transaction_id":"<uuid>"}' \
 *           localhost:9090 az.bankradar.grpc.RiskStreamService/GetRiskAssessment
 *
 *   grpcurl -plaintext -d '{"subscriber_id":"test","min_risk_score":40}' \
 *           localhost:9090 az.bankradar.grpc.RiskStreamService/SubscribeToRiskEvents
 */
@GrpcService
@RequiredArgsConstructor
@Slf4j
public class RiskStreamGrpcService
        extends RiskStreamServiceGrpc.RiskStreamServiceImplBase {

    private final TransactionRepository transactionRepository;

    /** Map of subscriber StreamObserver → its minimum-score filter. */
    private final ConcurrentHashMap<StreamObserver<RiskEventProto>, Integer> subscribers =
            new ConcurrentHashMap<>();

    // ============================================================
    // RPC 1: Subscribe to streaming risk events
    // ============================================================
    @Override
    public void subscribeToRiskEvents(SubscribeRequest request,
                                      StreamObserver<RiskEventProto> responseObserver) {

        log.info("New gRPC subscriber: id={} minScore={}",
                request.getSubscriberId(), request.getMinRiskScore());

        // Cast to ServerCallStreamObserver so we can detect client cancellation
        ServerCallStreamObserver<RiskEventProto> serverObserver =
                (ServerCallStreamObserver<RiskEventProto>) responseObserver;

        serverObserver.setOnCancelHandler(() -> {
            log.info("Subscriber {} disconnected", request.getSubscriberId());
            subscribers.remove(responseObserver);
        });

        subscribers.put(responseObserver, request.getMinRiskScore());
        // Do NOT call onCompleted() — keep the stream open for pushing events.
    }

    // ============================================================
    // RPC 2: Get single transaction risk score
    // ============================================================
    @Override
    public void getRiskAssessment(GetRiskRequest request,
                                  StreamObserver<RiskEventProto> responseObserver) {

        UUID txId;
        try {
            txId = UUID.fromString(request.getTransactionId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid transaction_id UUID")
                    .asRuntimeException());
            return;
        }

        Optional<Transaction> tx = transactionRepository.findById(txId);
        if (tx.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Transaction " + txId + " not found")
                    .asRuntimeException());
            return;
        }

        responseObserver.onNext(toProto(tx.get()));
        responseObserver.onCompleted();
    }

    // ============================================================
    // Spring event listener — fan-out to all subscribers
    // ============================================================
    @EventListener
    public void onAlert(InternalAlertEvent event) {
        if (subscribers.isEmpty()) return;

        AlertMessage msg = event.getMessage();
        RiskEventProto proto = toProto(msg);

        log.debug("Fanning out gRPC event to {} subscriber(s), tx={}",
                subscribers.size(), msg.getTransactionId());

        // Iterate and remove disconnected observers
        subscribers.entrySet().removeIf(entry -> {
            StreamObserver<RiskEventProto> observer = entry.getKey();
            int minScore = entry.getValue();

            // Apply per-subscriber filter
            if (msg.getRiskScore() < minScore) return false;

            try {
                observer.onNext(proto);
                return false;     // keep
            } catch (Exception e) {
                log.warn("Removing failed subscriber: {}", e.getMessage());
                try { observer.onError(Status.INTERNAL.withCause(e).asRuntimeException()); }
                catch (Exception ignored) { /* ignore */ }
                return true;      // remove
            }
        });
    }

    // ============================================================
    // Mappers
    // ============================================================
    private RiskEventProto toProto(AlertMessage msg) {
        return RiskEventProto.newBuilder()
                .setTransactionId(msg.getTransactionId().toString())
                .setCustomerId(msg.getCustomerId())
                .setAmount(msg.getAmount().doubleValue())
                .setCurrency(msg.getCurrency() == null ? "" : msg.getCurrency())
                .setCountry(msg.getCountry() == null ? "" : msg.getCountry())
                .setRiskScore(msg.getRiskScore())
                .setStatus(mapStatus(msg.getStatus()))
                .setReasons(msg.getReasons() == null ? "" : msg.getReasons())
                .setTimestamp(msg.getTimestamp().toString())
                .build();
    }

    private RiskEventProto toProto(Transaction tx) {
        return RiskEventProto.newBuilder()
                .setTransactionId(tx.getId().toString())
                .setCustomerId(tx.getCustomerId())
                .setAmount(tx.getAmount().doubleValue())
                .setCurrency(tx.getCurrency())
                .setCountry(tx.getCountry())
                .setRiskScore(tx.getRiskScore() == null ? 0 : tx.getRiskScore())
                .setStatus(mapStatus(tx.getRiskStatus()))
                .setReasons("")
                .setTimestamp(tx.getTimestamp().toString())
                .build();
    }

    private RiskStatusProto mapStatus(Transaction.RiskStatus status) {
        if (status == null) return RiskStatusProto.PENDING;
        return switch (status) {
            case PENDING    -> RiskStatusProto.PENDING;
            case SAFE       -> RiskStatusProto.SAFE;
            case SUSPICIOUS -> RiskStatusProto.SUSPICIOUS;
            case FRAUD      -> RiskStatusProto.FRAUD;
        };
    }
}
