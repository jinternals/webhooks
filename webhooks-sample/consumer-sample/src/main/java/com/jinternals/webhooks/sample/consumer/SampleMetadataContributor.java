package com.jinternals.webhooks.sample.consumer;

import com.jinternals.webhooks.consumer.metadata.MetadataContributor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Demo {@link MetadataContributor}: copies a vendor-specific {@code x-event-source} header into the
 * metadata attributes map, so handlers can read {@code metadata.attribute("eventSource")}.
 */
@Component
public class SampleMetadataContributor implements MetadataContributor {

    @Override
    public void contribute(HttpServletRequest request, Map<String, String> headers, Map<String, Object> attributes) {
        String source = headers.get("x-event-source");
        if (source != null) {
            attributes.put("eventSource", source);
        }
    }
}
