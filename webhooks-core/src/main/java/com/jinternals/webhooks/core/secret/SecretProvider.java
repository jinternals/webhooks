package com.jinternals.webhooks.core.secret;

import java.util.List;

/**
 * Resolves the HMAC secret(s) for a webhook {@code target} (see SPEC.md §8a). This is the shared
 * extension point for secret sourcing and rotation on <em>both</em> sides of Standard Webhooks:
 *
 * <ul>
 *   <li>the <strong>consumer</strong> implements {@code SecretProvider<Endpoint>} to resolve the
 *       secret(s) an inbound signature is <em>validated</em> against;</li>
 *   <li>the <strong>producer</strong> implements {@code SecretProvider<Destination>} to resolve the
 *       secret an outbound envelope is <em>signed</em> with.</li>
 * </ul>
 *
 * <p>The target type is generic so neither side's configuration model leaks into {@code webhooks-core}.
 * Implement this to pull secrets from a secrets manager, a database, or a rotating cache instead of
 * static configuration.
 *
 * <p>Returning <strong>more than one</strong> secret enables zero-downtime rotation: the consumer's
 * verifier accepts a signature made with <em>any</em> returned secret, so a new secret can be
 * published to the sender while still honouring the old one, then the old one retired later. (A
 * producer dual-signs with <em>every</em> returned secret, emitting one signature per secret in a
 * single space-separated header, so a rotation is honoured on both sides simultaneously.)
 *
 * @param <T> the per-side target the secret is resolved for (consumer {@code Endpoint},
 *            producer {@code Destination}).
 */
public interface SecretProvider<T> {

    /**
     * The candidate secret(s) for this target, newest first. Never {@code null}; an empty list
     * means "no secret configured" and verification fails closed (and signing fails fast).
     */
    List<String> secretsFor(T target);
}
