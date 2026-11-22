package com.jinternals.webhooks.sample.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SampleMetadataContributorTest {

    private final SampleMetadataContributor contributor = new SampleMetadataContributor();

    @Test
    void copiesEventSourceHeaderIntoAttributes() {
        Map<String, String> headers = Map.of("x-event-source", "crm-prod");
        Map<String, Object> attributes = new HashMap<>();

        contributor.contribute(mock(HttpServletRequest.class), headers, attributes);

        assertThat(attributes).containsEntry("eventSource", "crm-prod");
    }

    @Test
    void doesNothingWhenHeaderAbsent() {
        Map<String, String> headers = Map.of("other", "x");
        Map<String, Object> attributes = new HashMap<>();

        contributor.contribute(mock(HttpServletRequest.class), headers, attributes);

        assertThat(attributes).isEmpty();
    }
}
