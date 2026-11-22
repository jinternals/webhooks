package com.jinternals.webhooks.consumer.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the webhook consumer (see SPEC.md §6). Consumer-only: declares the listen
 * paths, each with its own HMAC secret. {@code @WebhookType} event classes are discovered with zero config by
 * scanning the host application's auto-configuration packages (no base package to configure).
 *
 * <p>{@link Validated @Validated}: invalid configuration fails the context at <strong>bind time</strong>
 * with a clear message, rather than later with a raw executor/runtime exception (SPEC §14a). This covers a
 * non-positive pool size, queue capacity, body-size cap or idempotency cap; {@code core-pool-size} exceeding
 * {@code max-pool-size}; a null/non-positive async timeout; a negative replay tolerance; a null
 * unknown-type policy or handler mode; and an endpoint with a blank or non-absolute path.
 */
@Data
@Validated
@ConfigurationProperties("webhooks")
public class WebhookProperties {

    @Valid
    private Consumer consumer = new Consumer();

    @Data
    public static class Consumer {
        /** What to do when an incoming "type" has no matching {@code @WebhookType} class. */
        @NotNull
        private UnknownTypePolicy unknownTypePolicy = UnknownTypePolicy.ACK_AND_IGNORE;

        /** Best-effort in-memory dedupe on webhook-id (off by default; see SPEC.md §11). */
        private boolean inMemoryIdempotency = false;

        /**
         * Hard upper bound on retained ids for the in-memory idempotency guard (see SPEC.md §18).
         * Because the key is the attacker-influenced {@code webhook-id}, the map is size-capped so a
         * flood of unique ids cannot exhaust memory; the oldest entries are evicted when full. Default
         * 100,000.
         */
        @Positive
        private int inMemoryIdempotencyMaxSize = 100_000;

        /**
         * Maximum size of an inbound webhook request body (see SPEC.md §14a). Bodies larger than this
         * are rejected with {@code 413 Payload Too Large} <em>before</em> verification, so an
         * oversized or slow POST cannot exhaust memory or threads. Default 256&nbsp;KB.
         */
        private DataSize maxBodySize = DataSize.ofKilobytes(256);

        /**
         * Validate every configured endpoint secret at <strong>startup</strong> (see SPEC.md §14a).
         * A missing/blank/invalid secret then fails fast at boot instead of surfacing as a per-request
         * {@code 500} that a sender would retry forever. On by default.
         */
        private boolean validateSecretsOnStartup = true;

        /**
         * Whether inbound requests have their <strong>signature verified</strong> (see SPEC.md §8f). Enabled
         * by default: {@link com.jinternals.webhooks.consumer.verify.DefaultWebhookVerifier} checks the
         * Standard Webhooks {@code webhook-signature} header against the endpoint's secret(s) ({@code whsec_}
         * symmetric {@code v1} or {@code whpk_} asymmetric {@code v2a}).
         *
         * <p>Set to {@code false} to wire the
         * {@link com.jinternals.webhooks.consumer.verify.NoOpWebhookVerifier}: every request is then accepted
         * unconditionally and no endpoint secret is required.
         *
         * <p><strong>Security note.</strong> With verification off the endpoint accepts forged payloads from
         * anyone who can reach it — there is no message-authentication layer at all. Only disable when
         * authenticity is guaranteed by the transport instead (an authenticating reverse proxy / mTLS / a
         * Spring Security resource-server chain in front of the endpoint). The autoconfiguration logs a
         * prominent WARN at every startup while this is active.
         */
        private boolean signatureVerificationEnabled = true;

        /**
         * Replay-window (timestamp freshness) check (see SPEC.md §8b).
         *
         * <p><strong>Enabled by default</strong> (secure by default per the Standard Webhooks specification):
         * a request whose {@code webhook-timestamp} is outside {@code replay-tolerance} of the server clock
         * (in either direction) is rejected with a {@code 400}. Set to {@code false} to wire the
         * {@link com.jinternals.webhooks.consumer.verify.NoOpTimestampValidator} and accept any correctly
         * signed request regardless of timestamp age.
         *
         * <p><strong>Security note.</strong> The replay window is what lets a stateless consumer resist
         * captured-request replay attacks. Disabling it means an attacker who records one valid delivery can
         * re-send it indefinitely.
         */
        private boolean replayWindowEnabled = true;

        /**
         * Allowed clock skew for the replay-window check when {@link #replayWindowEnabled} is true (see
         * SPEC.md §8b). A request is accepted only if {@code |now - webhook-timestamp| <= tolerance}.
         * Default 5 minutes, matching the Standard Webhooks reference tolerance. Ignored when the replay
         * window is disabled.
         */
        @NotNull
        private Duration replayTolerance = Duration.ofMinutes(5);

        /** How {@code @WebhookHandler} methods are executed (see SPEC.md §15, §18). */
        @Valid
        @NestedConfigurationProperty
        private Handler handler = new Handler();

        @Valid
        @NestedConfigurationProperty
        private List<Endpoint> endpoints = new ArrayList<>();

        /**
         * Reject a zero/negative {@code max-body-size} at bind time (DataSize has no numeric constraint
         * annotation). A non-positive cap would reject every non-empty body as "too large", silently breaking
         * all delivery; failing fast at startup surfaces the misconfiguration instead (SPEC §14a).
         */
        @AssertTrue(message = "webhooks.consumer.max-body-size must be positive")
        public boolean isMaxBodySizePositive() {
            return maxBodySize != null && maxBodySize.toBytes() > 0;
        }

        /**
         * Reject a negative {@code replay-tolerance} at bind time. A negative tolerance would make the
         * replay-window check (when enabled) reject every request — {@code |now - ts| <= negative} is never
         * true — so fail fast rather than silently rejecting valid deliveries (SPEC §8b).
         */
        @AssertTrue(message = "webhooks.consumer.replay-tolerance must not be negative")
        public boolean isReplayToleranceNonNegative() {
            return replayTolerance != null && !replayTolerance.isNegative();
        }
    }

    /**
     * Handler execution tuning (see SPEC.md §15, §18).
     *
     * <ul>
     *   <li>{@code sync} (default) — run the handler inline; the {@code 2xx} means "processed".</li>
     *   <li>{@code async} — hand the handler to a bounded pool and return {@code 204} immediately;
     *       the {@code 2xx} means "accepted". Use when handlers are slow and the sender's timeout is
     *       tight, accepting that a crash before the handler runs drops that delivery.</li>
     * </ul>
     */
    @Data
    public static class Handler {
        public enum Mode {
            SYNC,
            ASYNC
        }

        /** Execution mode. Default {@code SYNC}. */
        @NotNull
        private Mode mode = Mode.SYNC;

        /** Async core pool size. */
        @Positive
        private int corePoolSize = 4;

        /** Async max pool size. */
        @Positive
        private int maxPoolSize = 16;

        /** Async bounded work queue capacity. A full queue applies back-pressure via CallerRunsPolicy (the task runs on the request thread). */
        @Positive
        private int queueCapacity = 1000;

        /** Per-handler timeout in async mode; a handler exceeding it is interrupted and logged. */
        @NotNull
        private Duration timeout = Duration.ofSeconds(30);

        /**
         * Reject {@code core-pool-size > max-pool-size} at bind time. {@link java.util.concurrent.ThreadPoolExecutor}
         * throws {@link IllegalArgumentException} for that combination only when the async pool is first
         * constructed (lazily, on the first async delivery) — validating here fails fast at startup with a
         * clear message instead (SPEC §14a, §18).
         */
        @AssertTrue(message = "webhooks.consumer.handler.core-pool-size must not exceed max-pool-size")
        public boolean isPoolSizingValid() {
            return corePoolSize <= maxPoolSize;
        }

        /**
         * Reject a zero/negative async {@code timeout} at bind time. A non-positive timeout would schedule the
         * handler's cancel immediately, interrupting every async handler the instant it starts; fail fast
         * instead (SPEC §18).
         */
        @AssertTrue(message = "webhooks.consumer.handler.timeout must be positive")
        public boolean isTimeoutPositive() {
            return timeout != null && !timeout.isNegative() && !timeout.isZero();
        }
    }

    @Data
    public static class Endpoint {
        /** Listen path, e.g. /webhooks/crm. Must be an absolute path starting with '/'. */
        @NotBlank
        @Pattern(regexp = "^/.*", message = "must be an absolute path starting with '/'")
        private String path;

        /**
         * Verification secret for this endpoint. The prefix selects the Standard Webhooks scheme:
         * {@code whsec_...} for symmetric {@code v1} (HMAC-SHA256), or {@code whpk_...} for an Ed25519
         * public key ({@code v2a} asymmetric; see SPEC.md §8e). A {@code whpk_} key additionally requires
         * {@link #endpointId}.
         */
        private String secret;

        /**
         * Additional accepted secrets for zero-downtime rotation (see SPEC.md §8a). During a rotation
         * window a signature made with the primary {@link #secret} <em>or</em> any secret here is
         * accepted, so the new secret can be published to the sender before the old one is retired.
         * Empty in the common (non-rotating) case. For {@code v2a} this is the spec's <em>trust list</em>
         * of public keys — the verifier accepts the webhook if any configured key verifies any signature.
         */
        private List<String> additionalSecrets = new ArrayList<>();

        /**
         * Stable endpoint identifier for the <strong>asymmetric {@code v2a}</strong> scheme only (see
         * SPEC.md §8e). The verifier prepends it to the content it rebuilds
         * ({@code endpoint_id.msg_id.timestamp.payload}) before checking the Ed25519 signature, which is
         * what lets one key pair safely serve many endpoints — a signature made for a different endpoint
         * fails here. It is <strong>never received on the wire</strong>: it must be set to the
         * <em>same</em> value the producer signs with, shared out-of-band when the endpoint is registered.
         *
         * <p>Required when {@link #secret} (or any {@link #additionalSecrets} entry) is a {@code whpk_} key;
         * ignored for symmetric {@code whsec_} secrets. Must not contain a {@code '.'} (the signed-content
         * delimiter).
         */
        private String endpointId;

        /**
         * Optional allow-list of event {@code type}s this endpoint accepts (see SPEC.md §6).
         *
         * <ul>
         *   <li>empty / omitted — accept any known {@code @WebhookType} (the common case);</li>
         *   <li>one entry — a single-type endpoint (e.g. {@code [contact.created]});</li>
         *   <li>many entries — a multi-type endpoint (e.g. {@code [contact.created, contact.updated]}).</li>
         * </ul>
         *
         * A type outside this list is treated as an unknown type for this endpoint and handled by
         * {@link Consumer#getUnknownTypePolicy()}.
         */
        private List<String> types = new ArrayList<>();

        /** True when this endpoint accepts the given event type (empty list = accept all). */
        public boolean accepts(String type) {
            return types.isEmpty() || types.contains(type);
        }
    }

    public enum UnknownTypePolicy {
        /** Return 204 so the sender stops retrying an event we don't model. */
        ACK_AND_IGNORE,
        /** Return 400. */
        REJECT
    }
}
