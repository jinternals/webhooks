package com.jinternals.webhooks.producer.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link SafeUrl} (SPEC.md §19c): a destination URL rendered for a log or exception message
 * must keep scheme/host/port/path but drop userinfo and query, so embedded credentials or query-string
 * tokens never reach a log sink.
 */
class SafeUrlTest {

    @Test
    void utilityClassHasAPrivateConstructor() throws Exception {
        // SafeUrl is a static-only utility; its private no-arg constructor exists only to prevent
        // instantiation. Invoke it via reflection so the line is covered (it carries no behaviour).
        Constructor<SafeUrl> ctor = SafeUrl.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull();
    }

    @Test
    void keepsSchemeHostAndPath() {
        assertThat(SafeUrl.sanitize("https://crm.example.com/webhooks")).isEqualTo("https://crm.example.com/webhooks");
    }

    @Test
    void keepsAnExplicitPort() {
        assertThat(SafeUrl.sanitize("https://crm.example.com:8443/webhooks"))
                .isEqualTo("https://crm.example.com:8443/webhooks");
    }

    @Test
    void stripsUserInfoCredentials() {
        // https://user:token@host/path -> the credentials must be removed.
        assertThat(SafeUrl.sanitize("https://alice:s3cr3t@crm.example.com/webhooks"))
                .isEqualTo("https://crm.example.com/webhooks")
                .doesNotContain("s3cr3t")
                .doesNotContain("alice");
    }

    @Test
    void stripsTheQueryString() {
        assertThat(SafeUrl.sanitize("https://crm.example.com/webhooks?access_token=abc123"))
                .isEqualTo("https://crm.example.com/webhooks")
                .doesNotContain("abc123");
    }

    @Test
    void stripsBothUserInfoAndQuery() {
        assertThat(SafeUrl.sanitize("https://u:p@crm.example.com:9000/hook?t=secret"))
                .isEqualTo("https://crm.example.com:9000/hook")
                .doesNotContain("secret")
                .doesNotContain("p@");
    }

    @Test
    void handlesAUrlWithNoPath() {
        assertThat(SafeUrl.sanitize("https://crm.example.com")).isEqualTo("https://crm.example.com");
    }

    @Test
    void handlesAUrlWithNoPathButAQuery() {
        // No path, with a query to strip: must not append a null path and must drop the query.
        assertThat(SafeUrl.sanitize("https://crm.example.com?t=secret"))
                .isEqualTo("https://crm.example.com")
                .doesNotContain("secret");
    }

    @Test
    void handlesASchemeRelativeUrlWithAHost() {
        // "//host/path" parses to a URI with a host but a null scheme: the "://" prefix must be omitted.
        assertThat(SafeUrl.sanitize("//crm.example.com/webhooks?t=secret"))
                .isEqualTo("crm.example.com/webhooks")
                .doesNotContain("secret");
    }

    @Test
    void rendersNullAsAPlaceholder() {
        assertThat(SafeUrl.sanitize(null)).isEqualTo("<null>");
    }

    @Test
    void stripsTheQueryFromAnUnparseableUrl() {
        // A syntactically invalid URL still must not leak a trailing ?token=… — fall back to the prefix.
        assertThat(SafeUrl.sanitize("ht tp://broken/path?token=leak"))
                .isEqualTo("ht tp://broken/path")
                .doesNotContain("leak");
    }

    @Test
    void returnsAnUnparseableUrlWithoutQueryUnchanged() {
        // Unparseable and no query: returned as-is (there is no secret-bearing query to remove).
        assertThat(SafeUrl.sanitize("ht tp://broken/path")).isEqualTo("ht tp://broken/path");
    }

    @Test
    void stripsQueryFromAHostlessButParseableValue() {
        // A URI that parses but has no host (e.g. mailto/opaque) falls back to the pre-'?' prefix.
        assertThat(SafeUrl.sanitize("mailto:ops@example.com?subject=secret"))
                .isEqualTo("mailto:ops@example.com")
                .doesNotContain("secret");
    }

    @Test
    void returnsAHostlessParseableValueWithoutQueryUnchanged() {
        assertThat(SafeUrl.sanitize("mailto:ops@example.com")).isEqualTo("mailto:ops@example.com");
    }

    @Test
    void stripsAQueryThatStartsAtTheVeryFirstCharacter() {
        // A value that is nothing but a query ("?token=…") parses to a hostless URI; stripQuery must cut at
        // index 0 (q >= 0, not q > 0) so the entire secret-bearing query is removed, leaving an empty string.
        assertThat(SafeUrl.sanitize("?token=secret")).isEmpty();
    }

    @Test
    void handlesAHostlessValueThatLacksAScheme() {
        // A bare path (no scheme, no host) with a query: the query is stripped via the fallback.
        assertThat(SafeUrl.sanitize("/just/a/path?t=secret"))
                .isEqualTo("/just/a/path")
                .doesNotContain("secret");
    }

    @Test
    void stripsUserInfoFromAnUnparseableUrl() {
        // An unparseable URL (space in the path) with credentials in the authority: the fallback must still
        // drop the userinfo so "user:pass@" cannot leak, even though URI parsing failed.
        assertThat(SafeUrl.sanitize("https://user:s3cr3t@host/bad path"))
                .isEqualTo("https://host/bad path")
                .doesNotContain("s3cr3t")
                .doesNotContain("user:");
    }

    @Test
    void stripsBothUserInfoAndQueryFromAnUnparseableUrl() {
        assertThat(SafeUrl.sanitize("https://user:s3cr3t@host/bad path?token=leak"))
                .isEqualTo("https://host/bad path")
                .doesNotContain("s3cr3t")
                .doesNotContain("leak");
    }

    @Test
    void doesNotMistakeAnAtInThePathForUserInfoInTheFallback() {
        // The '@' is in the PATH, not the authority (there is no userinfo). The unparseable fallback must not
        // strip from the authority start to this later '@'. Space in the path forces the fallback.
        assertThat(SafeUrl.sanitize("https://host/a@b/bad path")).isEqualTo("https://host/a@b/bad path");
    }

    @Test
    void stripsUserInfoFromASchemeRelativeUnparseableUrl() {
        // A scheme-relative value ("//user@host/…") still has its userinfo stripped by the fallback: the
        // prefix before "//" is empty, so group 1 is just "//". Space in the path forces the fallback path.
        assertThat(SafeUrl.sanitize("//alice:s3cr3t@host/bad path"))
                .isEqualTo("//host/bad path")
                .doesNotContain("s3cr3t");
    }

    @Test
    void leavesAnUnparseableUrlWithoutUserInfoUnchangedByTheFallback() {
        // No userinfo in the authority and no query: the fallback returns it unchanged (there is no secret to
        // strip). Space in the path forces the fallback. Pins that stripUserInfo does not over-match.
        assertThat(SafeUrl.sanitize("https://host/bad path")).isEqualTo("https://host/bad path");
    }

    @Test
    void redactMessageReplacesTheRawUrlWithItsSanitizedForm() {
        // The HTTP client echoes the raw URL into its exception message; redactMessage must replace that exact
        // occurrence with the sanitized URL so userinfo/query secrets don't reach a log via the cause message.
        String raw = "https://alice:s3cr3t@crm.example.com/webhooks?token=LEAK";
        String message = "I/O error on POST request for \"" + raw + "\": Connection refused";
        assertThat(SafeUrl.redactMessage(message, raw))
                .contains("https://crm.example.com/webhooks")
                .contains("Connection refused")
                .doesNotContain("s3cr3t")
                .doesNotContain("LEAK");
    }

    @Test
    void redactMessageIsANoOpWhenTheUrlIsAbsentFromTheMessage() {
        assertThat(SafeUrl.redactMessage("some unrelated error", "https://host/path"))
                .isEqualTo("some unrelated error");
    }

    @Test
    void redactMessageHandlesNullInputs() {
        assertThat(SafeUrl.redactMessage(null, "https://host")).isNull();
        assertThat(SafeUrl.redactMessage("msg", null)).isEqualTo("msg");
        assertThat(SafeUrl.redactMessage("msg", "")).isEqualTo("msg");
    }
}
