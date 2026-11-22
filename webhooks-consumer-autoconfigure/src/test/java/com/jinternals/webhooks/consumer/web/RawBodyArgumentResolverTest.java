package com.jinternals.webhooks.consumer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jinternals.webhooks.consumer.web.error.PayloadTooLargeException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;

class RawBodyArgumentResolverTest {

    static class Holder {
        @SuppressWarnings("unused")
        void handle(String body, Integer notString) {}
    }

    private MethodParameter param(int index) {
        return new MethodParameter(
                org.springframework.util.ReflectionUtils.findMethod(
                        Holder.class, "handle", String.class, Integer.class),
                index);
    }

    @Test
    void supportsOnlyStringParamOnController() {
        // The resolver only supports String on WebhookReceiveController, so Holder is never supported.
        RawBodyArgumentResolver resolver = new RawBodyArgumentResolver(1024);
        assertThat(resolver.supportsParameter(param(0))).isFalse(); // String, but wrong declaring class
        assertThat(resolver.supportsParameter(param(1))).isFalse(); // not String
    }

    @Test
    void supportsStringParamOnReceiveController() {
        RawBodyArgumentResolver resolver = new RawBodyArgumentResolver(1024);
        MethodParameter controllerParam = new MethodParameter(
                org.springframework.util.ReflectionUtils.findMethod(
                        WebhookReceiveController.class,
                        "handle",
                        jakarta.servlet.http.HttpServletRequest.class,
                        String.class),
                1);
        assertThat(resolver.supportsParameter(controllerParam)).isTrue();
    }

    @Test
    void doesNotSupportNonStringParamOnReceiveController() {
        // The HttpServletRequest parameter (index 0) is on the right declaring class but is NOT a
        // String, so the type check must veto it. Kills the mutant that forces String.class.equals
        // to always be true (which would wrongly claim support for the request param).
        RawBodyArgumentResolver resolver = new RawBodyArgumentResolver(1024);
        MethodParameter requestParam = new MethodParameter(
                org.springframework.util.ReflectionUtils.findMethod(
                        WebhookReceiveController.class,
                        "handle",
                        jakarta.servlet.http.HttpServletRequest.class,
                        String.class),
                0);
        assertThat(resolver.supportsParameter(requestParam)).isFalse();
    }

    @Test
    void readsBodyWithinLimit() throws Exception {
        RawBodyArgumentResolver resolver = new RawBodyArgumentResolver(1024);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        Object body =
                resolver.resolveArgument(param(0), new ModelAndViewContainer(), new ServletWebRequest(request), null);
        assertThat(body).isEqualTo("{\"a\":1}");
    }

    @Test
    void acceptsBodyExactlyAtLimit() throws Exception {
        // total == limit must pass (the guard is strictly `total > limit`). Paired with the
        // limit+1 rejection below this pins the boundary and kills the ConditionalsBoundary mutant.
        RawBodyArgumentResolver resolver = new RawBodyArgumentResolver(4);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("1234".getBytes(StandardCharsets.UTF_8)); // exactly 4 bytes

        Object body =
                resolver.resolveArgument(param(0), new ModelAndViewContainer(), new ServletWebRequest(request), null);
        assertThat(body).isEqualTo("1234");
    }

    @Test
    void rejectsChunkedBodyOneByteOverLimitWhenContentLengthUnknown() throws Exception {
        // Chunked Transfer-Encoding requests report Content-Length == -1L, bypassing the header fast-fail
        // and exercising the streaming byte counter at `total == limit + 1` (distinguishing `>` from `>=`).
        RawBodyArgumentResolver resolver = new RawBodyArgumentResolver(4);
        jakarta.servlet.http.HttpServletRequest chunkedOver = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(chunkedOver.getContentLengthLong()).thenReturn(-1L);
        when(chunkedOver.getInputStream())
                .thenReturn(new org.springframework.mock.web.DelegatingServletInputStream(
                        new java.io.ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> resolver.resolveArgument(
                        param(0), new ModelAndViewContainer(), new ServletWebRequest(chunkedOver), null))
                .isInstanceOf(PayloadTooLargeException.class);

        // And a chunked request of exactly 4 bytes (== limit) succeeds!
        jakarta.servlet.http.HttpServletRequest chunkedExact = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(chunkedExact.getContentLengthLong()).thenReturn(-1L);
        when(chunkedExact.getInputStream())
                .thenReturn(new org.springframework.mock.web.DelegatingServletInputStream(
                        new java.io.ByteArrayInputStream("1234".getBytes(StandardCharsets.UTF_8))));
        assertThat(resolver.resolveArgument(
                        param(0), new ModelAndViewContainer(), new ServletWebRequest(chunkedExact), null))
                .isEqualTo("1234");
    }

    @Test
    void rejectsBodyOverLimit() {
        RawBodyArgumentResolver resolver = new RawBodyArgumentResolver(4);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("123456789".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> resolver.resolveArgument(
                        param(0), new ModelAndViewContainer(), new ServletWebRequest(request), null))
                .isInstanceOf(PayloadTooLargeException.class)
                .satisfies(ex -> assertThat(((PayloadTooLargeException) ex).getLimitBytes())
                        .isEqualTo(4L));
    }

    @Test
    void returnsEmptyWhenNoNativeRequest() throws Exception {
        RawBodyArgumentResolver resolver = new RawBodyArgumentResolver(1024);
        org.springframework.web.context.request.NativeWebRequest webRequest =
                mock(org.springframework.web.context.request.NativeWebRequest.class);
        when(webRequest.getNativeRequest(jakarta.servlet.http.HttpServletRequest.class))
                .thenReturn(null);

        Object body = resolver.resolveArgument(param(0), new ModelAndViewContainer(), webRequest, null);
        assertThat(body).isEqualTo("");
    }

    @Test
    void fastFailsOversizedContentLengthHeaderBeforeOpeningInputStream() throws Exception {
        RawBodyArgumentResolver resolver = new RawBodyArgumentResolver(4);

        // Content-Length == 5 (> 4) fails immediately WITHOUT calling getInputStream()!
        jakarta.servlet.http.HttpServletRequest oversized = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(oversized.getContentLengthLong()).thenReturn(5L);
        assertThatThrownBy(() -> resolver.resolveArgument(
                        param(0), new ModelAndViewContainer(), new ServletWebRequest(oversized), null))
                .isInstanceOf(PayloadTooLargeException.class);
        org.mockito.Mockito.verify(oversized, org.mockito.Mockito.never()).getInputStream();

        // Content-Length == 4 (== limit) does NOT fast-fail and reads normally (pins > vs >= boundary).
        MockHttpServletRequest exact = new MockHttpServletRequest();
        exact.setContent("abcd".getBytes(StandardCharsets.UTF_8));
        assertThat(resolver.resolveArgument(param(0), new ModelAndViewContainer(), new ServletWebRequest(exact), null))
                .isEqualTo("abcd");
    }
}
