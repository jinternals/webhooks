package com.jinternals.webhooks.core.model;

/** Standard Webhooks header names (see STANDARD_WEBHOOKS.md §3). */
public final class WebhookHeaders {

    public static final String ID = "webhook-id";
    public static final String TIMESTAMP = "webhook-timestamp";
    public static final String SIGNATURE = "webhook-signature";

    private WebhookHeaders() {}
}
