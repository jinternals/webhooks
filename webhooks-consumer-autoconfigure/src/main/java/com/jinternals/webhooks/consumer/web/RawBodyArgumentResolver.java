package com.jinternals.webhooks.consumer.web;

import com.jinternals.webhooks.consumer.web.error.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies the {@code String rawBody} parameter of {@link WebhookReceiveController#handle} as the
 * request body read verbatim. Verifying the RAW bytes is required by Standard Webhooks
 * (see STANDARD_WEBHOOKS.md §4) — the body must not be re-serialized before verification.
 *
 * <p>The read is <strong>bounded</strong> by {@code webhooks.consumer.max-body-size} (see SPEC.md
 * §14a): it copies at most {@code limit + 1} bytes and throws {@link PayloadTooLargeException}
 * ({@code 413}) as soon as the body exceeds the limit, so an oversized or slow POST cannot exhaust
 * memory or threads before verification even runs.
 */
public class RawBodyArgumentResolver implements HandlerMethodArgumentResolver {

    private final long maxBodyBytes;

    public RawBodyArgumentResolver(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return String.class.equals(parameter.getParameterType())
                && WebhookReceiveController.class.equals(parameter.getDeclaringClass());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory)
            throws Exception {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            return "";
        }
        return readBounded(request.getInputStream());
    }

    /**
     * Read up to {@link #maxBodyBytes}; if more bytes are available, abort with
     * {@link PayloadTooLargeException} without buffering the whole body.
     */
    private String readBounded(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBodyBytes) {
                throw new PayloadTooLargeException(maxBodyBytes);
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
