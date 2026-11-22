# Transport authentication (bearer tokens / OAuth2)

How to authenticate the **HTTP transport** on top of the toolkit's own message signature — the producer
attaching an `Authorization: Bearer <token>`, and the consumer requiring and validating it in front of
the webhook endpoint. This is an **additive** layer: transport auth proves *who is calling*; the HMAC
signature proves *the message is genuine and untampered*. Keep both.

> For the signature itself (HMAC verification, replay window, rotation, SSRF, redaction) see
> [Verification & security](./verification-and-security.md). For the `WebhookTransport` SPI and the other
> replaceable beans see [Beans & extension points](../reference/beans-and-spis.md).

---

## Why both layers

The toolkit's **own** security model is the Standard Webhooks **HMAC-SHA256 signature**: the producer signs
`id.timestamp.payload` with the destination's shared `whsec_…` secret and the consumer verifies it. That is
the *message-authenticity* model and it is symmetric by design.

It is a separate and common requirement to also **authenticate the HTTP transport** on **both sides** — the
producer attaching an `Authorization: Bearer <token>` (typically from an **OAuth2 client-credentials**
grant) and the consumer requiring and validating that token in front of its endpoint. This is additive to
the HMAC signature (transport auth authenticates the *caller*; the signature authenticates the *message*)
and needs **no core change** to the toolkit: the producer side adds a `WebhookRequestInterceptor` bean, and
the consumer side is a standard Spring Security resource-server chain. Both ends are shown below.

---

## Producer — attach the bearer token on the way out

Publish a **`WebhookRequestInterceptor`** bean. It receives the fully-signed request and returns the headers
to send — the natural seam for adding an `Authorization` header. The toolkit applies it **inside** the
default transport chain, so you keep **all** the built-in delivery behaviour:

```
RetryingWebhookTransport → InterceptingWebhookTransport → RestClientWebhookTransport
  (retry / backoff /          (your interceptors,            (POST + SSRF/destination
   Retry-After / dead-letter)   e.g. the bearer token)         guard + URL redaction)
```

```java
@Bean
WebhookRequestInterceptor bearerTokenInterceptor(OAuth2AuthorizedClientManager clients) {
    return (url, headers, body) -> {
        String token = clients
                .authorize(OAuth2AuthorizeRequest.withClientRegistrationId("webhooks").principal("webhooks").build())
                .getAccessToken()
                .getTokenValue();
        var out = new LinkedHashMap<>(headers); // keep the signed webhook-* headers
        out.put("Authorization", List.of("Bearer " + token));
        return out;                             // return a NEW map; never mutate the input
    };
}
```

That is the whole change — no need to re-assemble the transport. Three properties fall out of running inside
the chain:

- **Retry and the SSRF guard still apply.** You are shaping the request, not replacing the transport, so
  `RetryingWebhookTransport` and the `DestinationUrlValidator` are untouched.
- **The HMAC signature is untouched.** The interceptor receives the already-signed `headers` and raw `body`;
  returning the signed `webhook-*` headers alongside your `Authorization` keeps both protections together.
- **Every retry re-evaluates the token.** Interceptors run per attempt, so if a short-lived token expires
  during backoff on a retryable failure (`5xx` / `429` / timeout), the next attempt picks up a fresh one
  automatically.

Multiple interceptors compose in Spring `@Order` order (auth, tenancy, tracing headers, …), each receiving
the previous one's output. `WebhookRequestInterceptor` is the **single** outbound-request-shaping strategy:
propagating a trace/correlation id to the consumer uses the exact same mechanism — copy the id from the
producer's `MDC` onto an agreed header. See
[SPEC §9b](../reference/SPEC.md#9b-correlation--trace-context-app-owned) for the end-to-end trace example,
which the sample apps implement in full.

> **Keep the `Authorization` value out of logs** — it is a bearer credential. The toolkit never logs
> request headers, so do not add one that does.

> **Do not replace `WebhookTransport` just to add a header.** A bare `@Bean WebhookTransport` lambda
> *replaces the entire default chain* and silently drops the built-in retry **and** the SSRF/destination
> guard. Reach for a replacement transport only when you are genuinely changing the **delivery mechanism**
> (e.g. enqueue to SQS/Kafka and deliver from a worker); reach for a `WebhookRequestInterceptor` when you
> are only shaping the outbound request.

---

## Consumer — require the bearer token on the way in

A receiver that additionally requires the *sender* to present a bearer token uses standard Spring Security
on the receiving application — a resource-server filter in front of the webhook endpoint — which is
**orthogonal** to this toolkit, which only verifies the webhook signature once a request reaches the
controller. The HMAC verification is not a substitute for, and does not interfere with, a Spring Security
filter chain on the same path. The two run in sequence: the filter chain validates the OAuth2 bearer token
first (a bad/absent token → `401` before the request ever reaches the webhook controller), then the toolkit
verifies the HMAC signature (a bad signature → `400`).

```java
// Consumer: require a valid OAuth2 bearer token on the webhook endpoints, in front of the toolkit's
// own HMAC verification. Scope this chain to the webhook paths so it does not touch the rest of the app.
@Bean
@Order(1)                                          // evaluated before the application's default chain
SecurityFilterChain webhookResourceServer(HttpSecurity http) throws Exception {
    http.securityMatcher("/webhooks/**")           // the webhooks.consumer.endpoints[].path prefix
            .authorizeHttpRequests(a -> a.anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())) // validate the JWT
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable); // token-authenticated, non-browser POSTs
    return http.build();
}
```

```yaml
# Consumer: point the resource server at the sender's authorization server for JWT validation.
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com/   # discovers the JWKS used to verify the bearer token
```

> **Order of defences on the consumer.** Transport auth (the `401` gate) and message auth (the signature
> `400`) are independent and complementary: the bearer token proves the *caller* is allowed to POST here;
> the HMAC signature proves the *message* was produced by the holder of the shared secret and was not
> altered in transit. Keep both — a valid token on a tampered body still fails signature verification, and
> a valid signature from an unauthorised caller still fails the token check.

---

## What about an asymmetric *message* model?

Transport auth does not change how the *message* is authenticated. An asymmetric / JWS / JWT **message**
security model (verifying authenticity with public keys instead of a shared HMAC secret) would replace the
signing scheme itself, not the transport. Making that pluggable (an SPI with the HMAC implementation as the
default bean, mirroring `WebhookTransport` / `SecretProvider` / `DestinationUrlValidator`) is a tracked,
additive enhancement — see [`SPEC.md` §17](../reference/SPEC.md#17-open-questions).
