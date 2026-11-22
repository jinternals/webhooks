package com.jinternals.webhooks.producer.security;

/**
 * Default {@link DestinationUrlValidator}: no restriction (see SPEC.md §19d). Any destination URL is
 * permitted, because destinations are supplied by the host through trusted configuration.
 *
 * <p>This is the shipped default. Restrict deliveries by configuring
 * {@code webhooks.producer.security.allowed-hosts} (which wires
 * {@link AllowListDestinationUrlValidator}) or by providing a custom {@code @Bean DestinationUrlValidator}.
 */
public class AllowAllDestinationUrlValidator implements DestinationUrlValidator {

    @Override
    public void validate(String url) {
        // No restriction — every destination is allowed.
    }
}
