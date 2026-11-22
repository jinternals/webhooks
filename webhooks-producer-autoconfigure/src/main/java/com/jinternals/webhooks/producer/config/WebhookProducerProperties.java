package com.jinternals.webhooks.producer.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the webhook producer (see SPEC.md §19). Declares the named destinations a
 * {@code @WebhookClient} can publish to, each with its own URL and signing secret.
 *
 * <pre>
 * webhooks:
 *   producer:
 *     destinations:
 *       crm:     { url: "https://crm.example.com/webhooks",     secret: "whsec_..." }
 *       billing: { url: "https://billing.example.com/webhooks", secret: "whsec_..." }
 * </pre>
 *
 * <p>{@link Validated @Validated}: a null HTTP connect/read timeout fails the context at <strong>bind
 * time</strong> with a clear message. The retry policy is deliberately <em>not</em> constrained here:
 * {@code max-attempts}, {@code jitter}, and the backoff bounds are instead clamped to safe ranges at runtime
 * by {@code RetryingWebhookTransport} (e.g. jitter is clamped into {@code [0,1]}, a sub-1 attempt count
 * becomes a single attempt), so a slightly off value degrades gracefully rather than failing startup — see
 * the retry documentation in README.md. Destination URL/secret completeness is validated separately at
 * startup by {@link DestinationValidator} (SPEC §19d).
 */
@Data
@Validated
@ConfigurationProperties("webhooks.producer")
public class WebhookProducerProperties {

    /** Named destinations keyed by the name used in {@code @WebhookClient(destination = "...")}. */
    private Map<String, Destination> destinations = new LinkedHashMap<>();

    /**
     * Validate every configured destination at startup (see SPEC.md §19d): a non-blank, parseable URL with a
     * host that passes the active {@link Security} URL guard, and at least one signing secret. A problem
     * fails the context at boot — fast and loud — instead of surfacing later as the first delivery's
     * exception, after a business event has already been produced. Enabled by default.
     */
    private boolean validateDestinationsOnStartup = true;

    /**
     * Whether outbound deliveries are <strong>signed</strong> (see SPEC.md §19h). Enabled by default:
     * {@link com.jinternals.webhooks.producer.sign.DefaultWebhookSigner} adds a Standard Webhooks
     * {@code webhook-signature} header over each payload ({@code whsec_} symmetric {@code v1} or {@code whsk_}
     * asymmetric {@code v2a}).
     *
     * <p>Set to {@code false} to wire the
     * {@link com.jinternals.webhooks.producer.sign.NoOpWebhookSigner}: deliveries then carry
     * <strong>no</strong> {@code webhook-signature} header and require no signing secret.
     *
     * <p><strong>Security note.</strong> Disabling signing removes the message-authentication layer — a
     * receiver that verifies signatures will reject the unsigned deliveries. Only disable when message
     * authenticity is guaranteed by the transport instead (OAuth2 bearer token / mTLS). The signature and
     * transport auth are complementary; prefer keeping both.
     */
    private boolean signingEnabled = true;

    /** Outbound HTTP connect/read timeouts for the default transport (see SPEC.md §19c). */
    @Valid
    private Http http = new Http();

    /** In-process (no-persistence) retry/backoff policy for the default transport (see SPEC.md §19b). */
    @Valid
    private Retry retry = new Retry();

    /** Outbound destination URL guard (SSRF protection; see SPEC.md §19d). */
    @Valid
    private Security security = new Security();

    @Data
    public static class Destination {

        /** Absolute URL the signed envelope is POSTed to, e.g. https://crm.example.com/webhooks. */
        private String url;

        /**
         * Signing secret for this destination. The prefix selects the Standard Webhooks scheme:
         * {@code whsec_...} for symmetric {@code v1} (HMAC-SHA256), or {@code whsk_...} for an Ed25519
         * private key ({@code v2a} asymmetric; see SPEC.md §19i). A {@code whsk_} key additionally
         * requires {@link #endpointId}.
         */
        private String secret;

        /**
         * Stable endpoint identifier for the <strong>asymmetric {@code v2a}</strong> scheme only (see
         * SPEC.md §19i). It is prepended to the signed content ({@code endpoint_id.msg_id.timestamp.payload}),
         * which is what lets one Ed25519 key pair safely serve many endpoints — a signature made for this
         * endpoint fails verification at any other. It is <strong>never sent on the wire</strong>: the
         * consumer must be configured with the <em>same</em> value out-of-band.
         *
         * <p>Required when {@link #secret} (or any {@link #additionalSecrets} entry) is a {@code whsk_} key;
         * ignored for symmetric {@code whsec_} secrets. Must not contain a {@code '.'} (the signed-content
         * delimiter).
         */
        private String endpointId;

        /**
         * Additional signing secrets for zero-downtime rotation (blue/green; see SPEC.md §19a). During
         * a rotation window the producer <strong>dual-signs</strong>: it signs the exact same payload
         * with the primary {@link #secret} <em>and</em> every secret here, and sends all signatures in
         * one space-separated {@code webhook-signature} header. A receiver that accepts the primary
         * <em>or</em> any additional secret verifies successfully, so the new secret can be rolled out
         * to receivers before the old one is retired (and vice versa). Empty in the common
         * (non-rotating) case.
         */
        private List<String> additionalSecrets = new ArrayList<>();
    }

    /**
     * Outbound HTTP timeouts applied to the default {@code RestClient} transport (see SPEC.md §19c). A
     * webhook delivery is a background call to a third party; without timeouts a stalled destination can
     * pin the calling thread indefinitely. A timeout surfaces as a {@link Destination}-level
     * {@code WebhookDeliveryException} and is treated as retryable by the retry policy.
     */
    @Data
    public static class Http {

        /** Maximum time to establish the TCP connection. Default 5 seconds. */
        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(5);

        /** Maximum time to wait for the response once connected. Default 10 seconds. */
        @NotNull
        private Duration readTimeout = Duration.ofSeconds(10);
    }

    /**
     * In-process retry/backoff for the default transport (see SPEC.md §19b). Retry is <strong>in-memory
     * and stateless</strong> — bounded attempts with exponential backoff and jitter, no queue, no disk,
     * no scheduler — so it never breaks the producer's no-persistence rule. Only <em>retryable</em>
     * failures are retried (5xx, 429, and transport errors such as timeouts); a 4xx is fatal and fails
     * immediately. After the attempts are exhausted the last {@code WebhookDeliveryException} is rethrown.
     */
    @Data
    public static class Retry {

        /**
         * Total delivery attempts, including the first. {@code 1} disables retry (one attempt, then
         * throw). Default {@code 3}. Values below {@code 1} are treated as {@code 1}.
         */
        private int maxAttempts = 3;

        /** Backoff before the second attempt; doubles each further attempt up to {@link #maxBackoff}. Default 500ms. */
        @NotNull
        private Duration initialBackoff = Duration.ofMillis(500);

        /** Upper bound on any single backoff after exponential growth. Default 5 seconds. */
        @NotNull
        private Duration maxBackoff = Duration.ofSeconds(5);

        /**
         * Random jitter fraction (0.0–1.0) applied to each backoff to avoid synchronized retry storms;
         * the actual wait is uniformly sampled from {@code [backoff*(1-jitter), backoff]}. Default 0.5.
         */
        private double jitter = 0.5;
    }

    /**
     * Outbound destination URL validation (SSRF protection; see SPEC.md §19d). <strong>Disabled by
     * default</strong>: with no allow-list configured, any destination URL is permitted (the host
     * controls destinations through trusted configuration). Populate {@link #allowedHosts} to restrict
     * deliveries to an explicit set of hosts; a destination outside the list then fails fast with a
     * {@code WebhookDeliveryException} before any request is sent.
     */
    @Data
    public static class Security {

        /**
         * Allow-list of destination hostnames (exact, case-insensitive match). Empty (the default) means
         * <strong>no restriction</strong>. When non-empty, only these hosts may be delivered to.
         */
        private List<String> allowedHosts = new ArrayList<>();
    }
}
