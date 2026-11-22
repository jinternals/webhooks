package com.jinternals.webhooks.producer.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link InterceptingWebhookTransport}: it must apply the configured
 * {@link WebhookRequestInterceptor}s in order (each receiving the previous one's output), pass the result
 * to the delegate unchanged, be a transparent pass-through when there are none, and reject a
 * {@code null}-returning interceptor rather than deliver with no headers.
 */
class InterceptingWebhookTransportTest {

    /** A delegate that records exactly what it was asked to send. */
    private static final class RecordingTransport implements WebhookTransport {
        String url;
        Map<String, List<String>> headers;
        String body;
        int sends;

        @Override
        public void send(String url, Map<String, List<String>> headers, String body) {
            this.url = url;
            this.headers = headers;
            this.body = body;
            this.sends++;
        }
    }

    @Test
    void withNoInterceptorsItDelegatesTheHeadersUnchanged() {
        // Empty chain: the exact same headers reference the caller passed must reach the delegate, and the
        // url/body must pass through verbatim. Proves the pass-through path does not alter the request.
        RecordingTransport delegate = new RecordingTransport();
        InterceptingWebhookTransport transport = new InterceptingWebhookTransport(delegate, List.of());
        Map<String, List<String>> headers = Map.of("webhook-id", List.of("msg_1"));

        transport.send("https://crm.example.com/webhooks", headers, "{\"a\":1}");

        assertThat(delegate.sends).isEqualTo(1);
        assertThat(delegate.url).isEqualTo("https://crm.example.com/webhooks");
        assertThat(delegate.body).isEqualTo("{\"a\":1}");
        assertThat(delegate.headers).isEqualTo(headers);
    }

    @Test
    void itAppliesASingleInterceptorBeforeDelegating() {
        // One interceptor that adds an Authorization header: the delegate must receive the signed header plus
        // the added one. Kills a mutant that skips interception (the header would be missing).
        RecordingTransport delegate = new RecordingTransport();
        WebhookRequestInterceptor addBearer = (url, headers, body) -> {
            var out = new LinkedHashMap<>(headers);
            out.put("Authorization", List.of("Bearer t"));
            return out;
        };
        InterceptingWebhookTransport transport = new InterceptingWebhookTransport(delegate, List.of(addBearer));

        transport.send("https://crm.example.com/webhooks", Map.of("webhook-id", List.of("msg_1")), "{}");

        assertThat(delegate.headers)
                .containsEntry("webhook-id", List.of("msg_1"))
                .containsEntry("Authorization", List.of("Bearer t"));
    }

    @Test
    void itAppliesInterceptorsInOrderEachSeeingThePreviousOutput() {
        // Two interceptors, where the second reads what the first produced. Recording the headers each one
        // observed proves ordering AND that the chain threads output->input (not both reading the original).
        RecordingTransport delegate = new RecordingTransport();
        List<Map<String, List<String>>> observedBySecond = new ArrayList<>();

        WebhookRequestInterceptor first = (url, headers, body) -> {
            var out = new LinkedHashMap<>(headers);
            out.put("X-Step", List.of("one"));
            return out;
        };
        WebhookRequestInterceptor second = (url, headers, body) -> {
            observedBySecond.add(headers); // must already contain X-Step=one from the first
            var out = new LinkedHashMap<>(headers);
            out.put("X-Step", List.of("one-two"));
            return out;
        };
        InterceptingWebhookTransport transport = new InterceptingWebhookTransport(delegate, List.of(first, second));

        transport.send("https://crm.example.com/webhooks", Map.of(), "{}");

        assertThat(observedBySecond).hasSize(1);
        assertThat(observedBySecond.get(0)).containsEntry("X-Step", List.of("one"));
        assertThat(delegate.headers).containsEntry("X-Step", List.of("one-two"));
    }

    @Test
    void itRejectsAnInterceptorThatReturnsNullAndDoesNotDeliver() {
        // A misbehaving interceptor returning null must fail loudly (naming the offending class) and the
        // delegate must NOT be called — delivering with null/empty headers would strip the signed webhook-*
        // headers. Kills the mutant that would let null flow through to the delegate.
        RecordingTransport delegate = new RecordingTransport();
        WebhookRequestInterceptor returnsNull = (url, headers, body) -> null;
        InterceptingWebhookTransport transport = new InterceptingWebhookTransport(delegate, List.of(returnsNull));

        assertThatThrownBy(() -> transport.send("https://crm.example.com/webhooks", Map.of(), "{}"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("returned null headers")
                .hasMessageContaining(returnsNull.getClass().getName());
        assertThat(delegate.sends).isZero();
    }

    @Test
    void theInterceptorListIsDefensivelyCopiedAtConstruction() {
        // Mutating the caller's list after construction must not change the transport's behaviour: the
        // constructor copies it. Proves List.copyOf is load-bearing (kills a mutant that stored the list as-is).
        RecordingTransport delegate = new RecordingTransport();
        List<WebhookRequestInterceptor> mutable = new ArrayList<>();
        InterceptingWebhookTransport transport = new InterceptingWebhookTransport(delegate, mutable);
        mutable.add((url, headers, body) -> {
            var out = new LinkedHashMap<>(headers);
            out.put("X-Late", List.of("added"));
            return out;
        });

        transport.send("https://crm.example.com/webhooks", Map.of(), "{}");

        assertThat(delegate.headers).doesNotContainKey("X-Late");
        assertThat(delegate.sends).isEqualTo(1);
    }

    @Test
    void itPassesUrlAndBodyToInterceptorsAndDelegateVerbatim() {
        // The url and body an interceptor observes (and the delegate receives) must be exactly those passed
        // in — proves neither is swapped/blanked on the way through.
        RecordingTransport delegate = new RecordingTransport();
        List<String> seenUrls = new ArrayList<>();
        List<String> seenBodies = new ArrayList<>();
        WebhookRequestInterceptor spy = (url, headers, body) -> {
            seenUrls.add(url);
            seenBodies.add(body);
            return headers;
        };
        InterceptingWebhookTransport transport = new InterceptingWebhookTransport(delegate, List.of(spy));

        assertThatCode(() -> transport.send("https://x.example.com/p", Map.of(), "{\"k\":true}"))
                .doesNotThrowAnyException();

        assertThat(seenUrls).containsExactly("https://x.example.com/p");
        assertThat(seenBodies).containsExactly("{\"k\":true}");
        assertThat(delegate.url).isEqualTo("https://x.example.com/p");
        assertThat(delegate.body).isEqualTo("{\"k\":true}");
    }
}
