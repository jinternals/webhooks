package com.jinternals.webhooks.producer.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import java.util.List;
import org.junit.jupiter.api.Test;

class DestinationUrlValidatorTest {

    @Test
    void allowAllAcceptsAnyUrl() {
        DestinationUrlValidator validator = new AllowAllDestinationUrlValidator();

        assertThatCode(() -> validator.validate("https://anything.example.com/hooks"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("http://169.254.169.254/latest/meta-data"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowListAcceptsHttpAndHttpsHostsOnTheList() {
        DestinationUrlValidator validator =
                new AllowListDestinationUrlValidator(List.of("crm.example.com", "billing.example.com"));

        assertThatCode(() -> validator.validate("https://crm.example.com/webhooks"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("http://billing.example.com/webhooks"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("HTTP://crm.example.com/webhooks"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowListRejectsUnsupportedOrMissingSchemeEvenForAllowedHost() {
        DestinationUrlValidator validator = new AllowListDestinationUrlValidator(List.of("crm.example.com"));

        assertThatThrownBy(() -> validator.validate("ftp://alice:s3cr3t@crm.example.com/file?token=secret"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("Unsupported destination URL scheme")
                .hasMessageNotContaining("s3cr3t")
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> validator.validate("//crm.example.com/webhooks"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("Unsupported destination URL scheme");
    }

    @Test
    void allowListMatchesHostCaseInsensitively() {
        DestinationUrlValidator validator = new AllowListDestinationUrlValidator(List.of("CRM.Example.com"));

        assertThatCode(() -> validator.validate("https://crm.EXAMPLE.com/webhooks"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowListRejectsAHostNotOnTheList() {
        DestinationUrlValidator validator = new AllowListDestinationUrlValidator(List.of("crm.example.com"));

        assertThatThrownBy(() -> validator.validate("https://evil.example.com/webhooks"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("Destination host not allowed")
                .hasMessageContaining("evil.example.com");
    }

    @Test
    void allowListRejectsAUrlWithNoHost() {
        DestinationUrlValidator validator = new AllowListDestinationUrlValidator(List.of("crm.example.com"));

        assertThatThrownBy(() -> validator.validate("https:///etc/passwd"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("no host");
    }

    @Test
    void allowListRejectsASyntacticallyInvalidUrl() {
        DestinationUrlValidator validator = new AllowListDestinationUrlValidator(List.of("crm.example.com"));

        assertThatThrownBy(() -> validator.validate("ht tp://bad url"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("Invalid destination URL");
    }
}
