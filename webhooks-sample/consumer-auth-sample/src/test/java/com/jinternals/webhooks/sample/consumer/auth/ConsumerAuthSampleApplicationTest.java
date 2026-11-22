package com.jinternals.webhooks.sample.consumer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jinternals.webhooks.core.model.WebhookHeaders;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.standardwebhooks.Webhook;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ConsumerAuthSampleApplicationTest {

    private static final String WEBHOOK_SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConsumerAuthSampleApplication.AuthenticatedContactHandlers handlers;

    @Value("${sample.auth.jwt-secret}")
    private String jwtSecret;

    private String mintJwt(String scope) {
        SecretKey key = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://producer.webhooks.local")
                .subject("webhooks-producer")
                .audience(List.of("webhooks-consumer"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("scope", scope)
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    @Test
    void rejectsMissingBearerTokenWith401EvenWhenWebhookSignatureIsValid() throws Exception {
        String msgId = "msg_auth_1";
        long ts = Instant.now().getEpochSecond();
        String body =
                "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\",\"fullName\":\"Ada\",\"email\":\"ada@example.com\"}}";
        String sig = new Webhook(WEBHOOK_SECRET).sign(msgId, ts, body);

        mockMvc.perform(post("/webhooks/crm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookHeaders.ID, msgId)
                        .header(WebhookHeaders.TIMESTAMP, Long.toString(ts))
                        .header(WebhookHeaders.SIGNATURE, sig)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsValidBearerTokenWith400WhenWebhookSignatureIsTampered() throws Exception {
        String token = mintJwt("webhooks:deliver");
        String body =
                "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\",\"fullName\":\"Ada\",\"email\":\"ada@example.com\"}}";

        mockMvc.perform(post("/webhooks/crm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .header(WebhookHeaders.ID, "msg_auth_2")
                        .header(
                                WebhookHeaders.TIMESTAMP,
                                Long.toString(Instant.now().getEpochSecond()))
                        .header(WebhookHeaders.SIGNATURE, "v1,dGFtcGVyZWQ=")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsRequestOnlyWhenBothBearerJwtAndWebhookSignatureAreValid() throws Exception {
        handlers.clear();
        String token = mintJwt("webhooks:deliver");
        String msgId = "msg_auth_3";
        long ts = Instant.now().getEpochSecond();
        String body =
                "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-77\",\"fullName\":\"Ada\",\"email\":\"ada@example.com\"}}";
        String sig = new Webhook(WEBHOOK_SECRET).sign(msgId, ts, body);

        mockMvc.perform(post("/webhooks/crm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .header(WebhookHeaders.ID, msgId)
                        .header(WebhookHeaders.TIMESTAMP, Long.toString(ts))
                        .header(WebhookHeaders.SIGNATURE, sig)
                        .content(body))
                .andExpect(status().isNoContent());

        assertThat(handlers.handledIds()).containsExactly("c-77");
    }
}
