package com.jinternals.webhooks.consumer.web.receive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit test for the sealed {@link Resolution} result type and its factory methods. */
class ResolutionTest {

    @Test
    void acceptedCarriesTheTypeAndEvent() {
        Object event = new Object();

        Resolution resolution = Resolution.accepted("contact.created", event);

        assertThat(resolution).isInstanceOf(Resolution.Accepted.class);
        Resolution.Accepted accepted = (Resolution.Accepted) resolution;
        assertThat(accepted.type()).isEqualTo("contact.created");
        assertThat(accepted.event()).isSameAs(event);
    }

    @Test
    void ignoredIsASharedInstance() {
        Resolution first = Resolution.ignored();
        Resolution second = Resolution.ignored();

        assertThat(first).isInstanceOf(Resolution.Ignored.class).isSameAs(second);
    }
}
