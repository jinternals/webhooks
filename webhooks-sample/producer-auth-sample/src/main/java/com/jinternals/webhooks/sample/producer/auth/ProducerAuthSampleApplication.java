package com.jinternals.webhooks.sample.producer.auth;

import com.jinternals.webhooks.core.annotations.WebhookType;
import com.jinternals.webhooks.producer.annotations.EnableWebhookClients;
import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import com.jinternals.webhooks.producer.transport.WebhookRequestInterceptor;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Producer sample demonstrating <strong>transport authentication</strong>
 * ({@code Authorization: Bearer <jwt>}) layered on top of Standard Webhooks {@code v1} HMAC signing via
 * {@link WebhookRequestInterceptor} (see {@code docs/guides/transport-authentication.md}).
 */
@SpringBootApplication
@EnableWebhookClients(clients = ProducerAuthSampleApplication.CrmWebhooks.class)
public class ProducerAuthSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProducerAuthSampleApplication.class, args);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @WebhookType("contact.created")
    public static class ContactCreated {
        private String id;
        private String fullName;
        private String email;
    }

    @WebhookClient(destination = "crm")
    public interface CrmWebhooks {
        void contactCreated(ContactCreated event);
    }

    @Bean
    JwtEncoder jwtEncoder(@Value("${sample.auth.jwt-secret}") String jwtSecret) {
        SecretKey key = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /**
     * Attaches a freshly minted short-lived JWT {@code Authorization: Bearer <token>} header inside the
     * default transport chain on every delivery attempt, while keeping the signed {@code webhook-*} headers
     * intact.
     */
    @Bean
    WebhookRequestInterceptor bearerTokenWebhookInterceptor(
            JwtEncoder jwtEncoder,
            @Value("${sample.auth.issuer}") String issuer,
            @Value("${sample.auth.audience}") String audience) {
        return (url, headers, body) -> {
            Instant now = Instant.now();
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer(issuer)
                    .subject("webhooks-producer")
                    .audience(List.of(audience))
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(300))
                    .claim("scope", "webhooks:deliver")
                    .build();
            String token = jwtEncoder
                    .encode(JwtEncoderParameters.from(
                            JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                    .getTokenValue();

            Map<String, List<String>> out = new LinkedHashMap<>(headers);
            out.put("Authorization", List.of("Bearer " + token));
            return out;
        };
    }

    @RestController
    static class AuthPublishController {
        private final CrmWebhooks crmWebhooks;

        AuthPublishController(CrmWebhooks crmWebhooks) {
            this.crmWebhooks = crmWebhooks;
        }

        @PostMapping("/publish/contact-created")
        ResponseEntity<String> publish(@RequestBody ContactCreated event) {
            try {
                crmWebhooks.contactCreated(event);
                return ResponseEntity.accepted().body("published");
            } catch (WebhookDeliveryException e) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("delivery failed: " + e.getMessage());
            }
        }
    }
}
