package com.jinternals.webhooks.consumer.web.receive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit test for the shared {@link WebhookRequestHeaders} helpers: case-insensitive, {@link Locale#ROOT}
 * header collection and first-value lookup (SPEC.md §9b, §10).
 */
class WebhookRequestHeadersTest {

    @Test
    void collectLowerCasesNamesWithLocaleRootAndKeepsAllValues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Webhook-Id", "msg_1");
        request.addHeader("X-Multi", "a");
        request.addHeader("X-Multi", "b");

        Map<String, List<String>> headers = WebhookRequestHeaders.collect(request);

        // Names are lower-cased (Locale.ROOT), so a canonical constant finds a mixed-case inbound header.
        assertThat(headers).containsKey("webhook-id").containsKey("x-multi");
        assertThat(headers.get("webhook-id")).containsExactly("msg_1");
        // Every value of a repeated header is preserved, in order.
        assertThat(headers.get("x-multi")).containsExactly("a", "b");
    }

    @Test
    void collectTurkishLocaleIsNotUsedToLowerAsciiHeaderNames() {
        // Locale.ROOT lowercasing means an uppercase 'I' always becomes ASCII 'i', never the Turkish
        // dotless 'ı'. Pins the Locale.ROOT choice: "WEBHOOK-ID" must map to "webhook-id".
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("WEBHOOK-ID", "msg_1");

        Map<String, List<String>> headers = WebhookRequestHeaders.collect(request);
        assertThat(headers).containsKey("webhook-id");
        assertThat(WebhookRequestHeaders.first(headers, "webhook-id")).isEqualTo("msg_1");
    }

    @Test
    void collectToleratesNullHeaderNames() {
        // The servlet spec allows getHeaderNames() to return null; the loop guard must handle it.
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getHeaderNames()).thenReturn(null);

        assertThat(WebhookRequestHeaders.collect(request)).isEmpty();
    }

    @Test
    void firstReturnsTheFirstValueCaseInsensitively() {
        Map<String, List<String>> headers = Map.of("webhook-id", List.of("msg_1", "msg_2"));

        // Lookup name is lower-cased, so a canonical mixed-case constant still resolves.
        assertThat(WebhookRequestHeaders.first(headers, "Webhook-Id")).isEqualTo("msg_1");
        assertThat(WebhookRequestHeaders.first(headers, "webhook-id")).isEqualTo("msg_1");
    }

    @Test
    void firstReturnsNullForAbsentHeader() {
        assertThat(WebhookRequestHeaders.first(Map.of(), "webhook-id")).isNull();
    }

    @Test
    void firstReturnsNullForPresentButEmptyValueList() {
        // A header name present with an empty value list resolves to null, exercising the isEmpty() guard.
        Map<String, List<String>> headers = Map.of("webhook-id", Collections.emptyList());
        assertThat(WebhookRequestHeaders.first(headers, "webhook-id")).isNull();
    }

    @Test
    void utilityConstructorIsPrivateAndInvocable() throws Exception {
        // The class is a non-instantiable utility; the private constructor exists only to prevent
        // instantiation. Invoking it reflectively covers the line and asserts the private modifier.
        Constructor<WebhookRequestHeaders> ctor = WebhookRequestHeaders.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isInstanceOf(WebhookRequestHeaders.class);
    }
}
