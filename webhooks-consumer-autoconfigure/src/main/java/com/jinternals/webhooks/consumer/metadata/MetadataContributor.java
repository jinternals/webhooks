package com.jinternals.webhooks.consumer.metadata;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * SPI for enriching {@code WebhookMetadata} with provider-specific fields (see SPEC.md §9). All
 * registered beans run after verification and before dispatch. Keep contributions cheap and never
 * attach secrets.
 */
public interface MetadataContributor {

    /** Add provider-specific fields to the attributes map. */
    void contribute(
            HttpServletRequest request,
            Map<String, @Nullable String> headers,
            Map<String, @Nullable Object> attributes);
}
