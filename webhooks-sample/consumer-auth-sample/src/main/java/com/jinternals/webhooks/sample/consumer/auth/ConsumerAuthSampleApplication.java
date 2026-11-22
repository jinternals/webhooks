package com.jinternals.webhooks.sample.consumer.auth;

import com.jinternals.webhooks.consumer.annotations.WebhookHandler;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.core.annotations.Redact;
import com.jinternals.webhooks.core.annotations.WebhookType;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;

/**
 * Consumer sample demonstrating dual-layer webhook security (see {@code docs/guides/transport-authentication.md}):
 *
 * <ol>
 *   <li>Spring Security OAuth2 Resource Server verifies the {@code Authorization: Bearer <jwt>} token first
 *       (missing/invalid token -&gt; {@code 401 Unauthorized});</li>
 *   <li>The Webhooks Toolkit verifies the {@code webhook-signature} + replay window next (missing/tampered
 *       signature -&gt; {@code 400 Bad Request}).</li>
 * </ol>
 */
@SpringBootApplication
public class ConsumerAuthSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsumerAuthSampleApplication.class, args);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @WebhookType("contact.created")
    public static class ContactCreated {
        @NotBlank
        private String id;

        @NotBlank
        private String fullName;

        @Redact
        private String email;
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${sample.auth.jwt-secret}") String jwtSecret) {
        SecretKey key = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain webhookResourceServer(HttpSecurity http) throws Exception {
        http.securityMatcher("/webhooks/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("SCOPE_webhooks:deliver"))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/**")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Component
    public static class AuthenticatedContactHandlers {
        private static final Logger log = LoggerFactory.getLogger(AuthenticatedContactHandlers.class);
        private final List<String> handledIds = new CopyOnWriteArrayList<>();

        @WebhookHandler
        public void onCreated(ContactCreated event, WebhookMetadata metadata) {
            handledIds.add(event.getId());
            log.info(
                    "Authenticated + verified contact.created id={} event={}", event.getId(), metadata.redactedEvent());
        }

        public List<String> handledIds() {
            return List.copyOf(handledIds);
        }

        public void clear() {
            handledIds.clear();
        }
    }
}
