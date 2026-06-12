package az.bankradar.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration for real-time fraud alerts.
 *
 * Bank staff connects via /ws and subscribes to /topic/alerts
 * (or to a private user-specific channel for branch-level filtering).
 *
 * Connect from JS:
 *   const socket = new SockJS('/ws');
 *   const stompClient = Stomp.over(socket);
 *   stompClient.connect({}, () => {
 *       stompClient.subscribe('/topic/alerts', msg => console.log(msg.body));
 *   });
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Topics where the server publishes; clients subscribe.
        registry.enableSimpleBroker("/topic", "/queue");
        // Prefix for messages bound to @MessageMapping methods.
        registry.setApplicationDestinationPrefixes("/app");
        // Prefix for user-specific destinations (per-branch alerts).
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
