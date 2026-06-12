package az.bankradar.grpc;

import az.bankradar.grpc.proto.RiskEventProto;
import az.bankradar.grpc.proto.RiskStreamServiceGrpc;
import az.bankradar.grpc.proto.SubscribeRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Example gRPC client — for manual testing or demonstration.
 *
 * Run this AFTER the BankRadar app is up and listening on port 9090:
 *
 *   ./mvnw exec:java -Dexec.mainClass=az.bankradar.grpc.SampleGrpcClient
 *
 * The client opens a server-streaming subscription and prints every alert
 * pushed by the server.  Send some suspicious transactions via REST to
 * see them appear here in real time.
 *
 * NOTE: This class is NOT used by the application at runtime; it's a
 * developer tool for demonstrating gRPC.
 */
public class SampleGrpcClient {

    public static void main(String[] args) throws InterruptedException {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 9090)
                .usePlaintext()             // for production: use TLS
                .build();

        RiskStreamServiceGrpc.RiskStreamServiceStub asyncStub =
                RiskStreamServiceGrpc.newStub(channel);

        CountDownLatch done = new CountDownLatch(1);

        SubscribeRequest request = SubscribeRequest.newBuilder()
                .setSubscriberId("demo-client")
                .setMinRiskScore(40)
                .build();

        System.out.println("Subscribing to risk events... (Ctrl+C to stop)");

        asyncStub.subscribeToRiskEvents(request, new StreamObserver<>() {
            @Override
            public void onNext(RiskEventProto event) {
                System.out.printf("[gRPC %s] tx=%s customer=%s score=%d reasons=%s%n",
                        event.getStatus(),
                        event.getTransactionId(),
                        event.getCustomerId(),
                        event.getRiskScore(),
                        event.getReasons());
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("Stream error: " + t.getMessage());
                done.countDown();
            }

            @Override
            public void onCompleted() {
                System.out.println("Stream completed");
                done.countDown();
            }
        });

        // Block until stream ends or 10 minutes pass
        done.await(10, TimeUnit.MINUTES);
        channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }
}
