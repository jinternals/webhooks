package com.jinternals.webhooks.consumer.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.consumer.support.Signing;
import com.jinternals.webhooks.core.model.WebhookHeaders;
import com.standardwebhooks.Webhook;
import com.standardwebhooks.exceptions.WebhookSigningException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class HmacWebhookVerifierTest {

    // Signature-focused: pair the verifier with the shipped default (window disabled).
    private final WebhookVerifier verifier = new HmacWebhookVerifier(new NoOpTimestampValidator());

    private Map<String, List<String>> headers(String id, String ts, String sig) {
        return Map.of(
                WebhookHeaders.ID, List.of(id),
                WebhookHeaders.TIMESTAMP, List.of(ts),
                WebhookHeaders.SIGNATURE, List.of(sig));
    }

    @Test
    void verifiesValidSignature() {
        String id = "msg_1";
        String ts = Signing.now();
        String body = "{\"type\":\"contact.created\"}";
        String sig = Signing.sign(Signing.SECRET, id, ts, body);

        // A well-formed single v1 part must be accepted: this pins the parts.length guard (if it were
        // forced true, every part would be skipped and this would throw).
        assertThatCode(() -> verifier.verify(List.of(Signing.SECRET), null, body, headers(id, ts, sig)))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsSignatureFromAnyRotationSecret() throws Exception {
        String id = "msg_2";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        // signed with the real secret, but a stale secret is listed first (rotation)
        String stale = "whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String sig = Signing.sign(Signing.SECRET, id, ts, body);

        verifier.verify(List.of(stale, Signing.SECRET), null, body, headers(id, ts, sig));
    }

    @Test
    void acceptsAnyOfSeveralSpaceSeparatedSignatures() throws Exception {
        String id = "msg_dual";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String good = Signing.sign(Signing.SECRET, id, ts, body);
        // A dual-signing sender: a junk signature plus the real one, space-separated.
        String combined = "v1,notarealsignature " + good;

        verifier.verify(List.of(Signing.SECRET), null, body, headers(id, ts, combined));
    }

    @Test
    void ignoresSignaturePartsThatAreMalformedOrWrongVersion() {
        String id = "msg_bad_parts";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        // One part has no comma, one is a different version — both skipped, nothing matches.
        String sig = "garbage v2,abc";

        assertThatThrownBy(() -> verifier.verify(List.of(Signing.SECRET), null, body, headers(id, ts, sig)))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("No matching signature");
    }

    @Test
    void safelyRejectsABareVersionTokenWithNoCommaSeparator() {
        // A signature part of exactly "v1" (no comma, so one token whose sole element equals the version)
        // must be skipped by the parts.length guard, not crash on parts[1]. This pins that guard: without
        // it, the wrong-version check passes ("v1".equals), parts[1] is read, and an index error escapes.
        String id = "msg_bare_version";
        String ts = Signing.now();
        String body = "{\"a\":1}";

        assertThatThrownBy(() -> verifier.verify(List.of(Signing.SECRET), null, body, headers(id, ts, "v1")))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("No matching signature");
    }

    @Test
    void rejectsATrailingCommaAppendedToAValidV1Signature() {
        // split(",", 2) keeps ",extra" attached to parts[1], so constant-time comparison rejects the tampered
        // token instead of ignoring everything after a second comma.
        String id = "msg_trailing";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String valid = Signing.sign(Signing.SECRET, id, ts, body);

        assertThatThrownBy(
                        () -> verifier.verify(List.of(Signing.SECRET), null, body, headers(id, ts, valid + ",extra")))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("No matching signature");
    }

    @Test
    void rejectsAnOtherwiseValidSignatureCarryingTheWrongVersionPrefix() {
        // Take a signature whose base64 is genuinely correct for this payload, but relabel its version
        // as v2. A correct version check must reject it; this pins the "v1".equals guard (without it, the
        // matching base64 would be accepted under the wrong version).
        String id = "msg_wrong_version";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String valid = Signing.sign(Signing.SECRET, id, ts, body); // "v1,<base64>"
        String wrongVersion = "v2," + valid.split(",")[1];

        assertThatThrownBy(() -> verifier.verify(List.of(Signing.SECRET), null, body, headers(id, ts, wrongVersion)))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("No matching signature");
    }

    @Test
    void throwsWhenNoSecretMatches() {
        String id = "msg_3";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String sig = Signing.sign(Signing.SECRET, id, ts, body);
        String wrong = "whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

        assertThatThrownBy(() -> verifier.verify(List.of(wrong), null, body, headers(id, ts, sig)))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void throwsWhenSecretsEmptyOrNull() {
        assertThatThrownBy(() -> verifier.verify(List.of(), null, "{}", Map.of()))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("No webhook secret");
        assertThatThrownBy(() -> verifier.verify(null, null, "{}", Map.of()))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void throwsWhenRequiredHeadersMissing() {
        assertThatThrownBy(() -> verifier.verify(List.of(Signing.SECRET), null, "{}", Map.of()))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("Missing required headers");
    }

    @Test
    void throwsWhenTimestampNotANumber() {
        String id = "msg_ts";
        String body = "{\"a\":1}";
        String sig = Signing.sign(Signing.SECRET, id, "not-a-number", body);

        assertThatThrownBy(() -> verifier.verify(List.of(Signing.SECRET), null, body, headers(id, "not-a-number", sig)))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("Invalid timestamp");
    }

    @Test
    void rejectsMalformedSecretAsMisconfiguration() {
        String id = "msg_mis";
        String ts = Signing.now();
        assertThatThrownBy(() -> verifier.verify(List.of(""), null, "{}", headers(id, ts, "v1,x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid webhook secret");
    }

    @Test
    void delegatesTheTimestampToTheValidatorBeforeCheckingSignatures() {
        // A custom validator records the timestamp it was handed and then rejects, proving the verifier
        // parses the header, delegates, and honours the validator's verdict (no signature work needed).
        AtomicLong seen = new AtomicLong(-1);
        WebhookVerifier custom = new HmacWebhookVerifier(ts -> {
            seen.set(ts);
            throw new WebhookVerificationException("nope from validator");
        });
        String id = "msg_delegate";
        String ts = "1700000000";
        String body = "{\"a\":1}";
        String sig = Signing.sign(Signing.SECRET, id, ts, body);

        assertThatThrownBy(() -> custom.verify(List.of(Signing.SECRET), null, body, headers(id, ts, sig)))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("nope from validator");
        assertThat(seen.get()).isEqualTo(1_700_000_000L);
    }

    @Test
    void throwsWhenOnlyTheSignatureHeaderIsMissing() {
        // Covers the third disjunct of the missing-headers guard independently (id + ts present, sig absent).
        Map<String, List<String>> headers = Map.of(
                WebhookHeaders.ID, List.of("msg_x"),
                WebhookHeaders.TIMESTAMP, List.of(Signing.now()));

        assertThatThrownBy(() -> verifier.verify(List.of(Signing.SECRET), null, "{}", headers))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("Missing required headers");
    }

    @Test
    void throwsWhenOnlyTheTimestampHeaderIsMissing() {
        // Covers the second disjunct of the missing-headers guard independently (id + sig present, ts absent).
        Map<String, List<String>> headers = Map.of(
                WebhookHeaders.ID, List.of("msg_x"),
                WebhookHeaders.SIGNATURE, List.of("v1,x"));

        assertThatThrownBy(() -> verifier.verify(List.of(Signing.SECRET), null, "{}", headers))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("Missing required headers");
    }

    @Test
    void treatsHeaderWithNullOrEmptyValueListAsMissing() {
        // Covers the firstHeader branch where the name matches but its value list is null or empty: the
        // header is treated as absent, so the missing-headers guard fires.
        Map<String, List<String>> nullValues = new HashMap<>();
        nullValues.put(WebhookHeaders.ID, null);
        nullValues.put(WebhookHeaders.TIMESTAMP, List.of(Signing.now()));
        nullValues.put(WebhookHeaders.SIGNATURE, List.of("v1,x"));

        assertThatThrownBy(() -> verifier.verify(List.of(Signing.SECRET), null, "{}", nullValues))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("Missing required headers");

        Map<String, List<String>> emptyValues = new HashMap<>();
        emptyValues.put(WebhookHeaders.ID, new ArrayList<>());
        emptyValues.put(WebhookHeaders.TIMESTAMP, List.of(Signing.now()));
        emptyValues.put(WebhookHeaders.SIGNATURE, List.of("v1,x"));

        assertThatThrownBy(() -> verifier.verify(List.of(Signing.SECRET), null, "{}", emptyValues))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("Missing required headers");
    }

    @Test
    void wrapsAnUnexpectedSigningFailureAsIllegalState() {
        // The official library never throws WebhookSigningException for an already-constructed secret, so
        // the catch is otherwise unreachable; override the doSign seam to force it (same approach as the
        // producer's signer) and prove the catch wraps it as an IllegalStateException.
        HmacWebhookVerifier failing = new HmacWebhookVerifier(new NoOpTimestampValidator()) {
            @Override
            String doSign(Webhook webhook, String msgId, long timestamp, String rawBody)
                    throws WebhookSigningException {
                throw new WebhookSigningException("boom");
            }
        };
        String id = "msg_sign_fail";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String sig = Signing.sign(Signing.SECRET, id, ts, body);

        assertThatThrownBy(() -> failing.verify(List.of(Signing.SECRET), null, body, headers(id, ts, sig)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to compute expected signature")
                .hasMessageContaining("boom");
    }
}
