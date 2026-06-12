package az.bankradar.alert;

import az.bankradar.grpc.InternalAlertEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent Events (SSE) endpoint — the gRPC streaming alternative
 * that works on Digital Ocean App Platform.
 *
 *  WHY SSE INSTEAD OF gRPC ON APP PLATFORM?
 *  ----------------------------------------
 *  App Platform exposes only ONE HTTP port per service (8080). gRPC on a
 *  separate port (9090) is not reachable from the internet. SSE solves the
 *  same problem — server pushes events to subscribed clients in real time —
 *  but uses plain HTTP, so it works through the existing port.
 *
 *  Compared to gRPC server-side streaming:
 *    - SSE is text/event-stream (UTF-8), gRPC is binary Protobuf
 *    - SSE is one-way (server → client only), gRPC supports bidirectional
 *    - SSE works from any HTTP client (browser EventSource, curl, etc.)
 *    - gRPC is faster and more compact but harder to consume from browsers
 *
 *  For BankRadar, both are wired to the SAME InternalAlertEvent — Spring
 *  events fan out to every listener.  Bank dashboards can use whichever
 *  protocol fits their stack.
 *
 *  Test from the command line:
 *    curl -N -H "Authorization: Bearer $TOKEN" \
 *         http://localhost:8080/api/alerts/stream
 *
 *  Test from a browser:
 *    const ev = new EventSource('/api/alerts/stream');
 *    ev.addEventListener('alert', e => console.log(JSON.parse(e.data)));
 */
@RestController
@RequestMapping("/api/alerts")
@Slf4j
public class AlertSseController {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('ANALYST', 'MANAGER', 'ADMIN')")
    public SseEmitter streamAlerts() {

        SseEmitter emitter = new SseEmitter(0L);  // 0 = no timeout, stream stays open

        emitters.add(emitter);
        log.info("New SSE subscriber connected (total: {})", emitters.size());

        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("SSE subscriber disconnected normally (remaining: {})", emitters.size());
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            log.debug("SSE subscriber timed out");
        });
        emitter.onError(ex -> {
            emitters.remove(emitter);
            log.debug("SSE subscriber errored: {}", ex.getMessage());
        });

        // Initial "connected" comment so the client knows the stream is alive
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }

        return emitter;
    }

    /**
     * Fan out internal Spring alert events to every connected SSE client.
     * The SAME event also drives WebSocket broadcast (AlertNotificationService)
     * and gRPC broadcast (RiskStreamGrpcService) — three transports, one source.
     */
    @EventListener
    public void onAlert(InternalAlertEvent event) {
        if (emitters.isEmpty()) return;

        AlertMessage msg = event.getMessage();
        emitters.removeIf(em -> {
            try {
                em.send(SseEmitter.event()
                        .id(msg.getTransactionId().toString())
                        .name("alert")
                        .data(msg, MediaType.APPLICATION_JSON));
                return false;
            } catch (Exception e) {
                log.debug("Removing failed SSE subscriber: {}", e.getMessage());
                return true;
            }
        });
    }
}
