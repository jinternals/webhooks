package com.jinternals.webhooks.producer.client.registrar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.client.WebhookPublisher;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit test for {@link WebhookClientFactoryBean}: it must report the client interface as its product type,
 * be a singleton, and build a JDK proxy that routes calls through the injected {@link WebhookPublisher}
 * (SPEC.md §19).
 */
class WebhookClientFactoryBeanTest {

    static class Event {}

    @WebhookClient(destination = "crm")
    interface CrmWebhooks {
        void contactCreated(Event event);
    }

    @SuppressWarnings("unchecked")
    private WebhookClientFactoryBean<CrmWebhooks> factoryBeanFor(WebhookPublisher publisher) {
        WebhookClientFactoryBean<CrmWebhooks> factoryBean = new WebhookClientFactoryBean<>(CrmWebhooks.class);
        ObjectProvider<WebhookPublisher> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(publisher);
        BeanFactory beanFactory = mock(BeanFactory.class);
        when(beanFactory.getBeanProvider(WebhookPublisher.class)).thenReturn(provider);
        factoryBean.setBeanFactory(beanFactory);
        return factoryBean;
    }

    @Test
    void objectTypeIsTheClientInterface() {
        assertThat(factoryBeanFor(mock(WebhookPublisher.class)).getObjectType()).isEqualTo(CrmWebhooks.class);
    }

    @Test
    void isSingleton() {
        assertThat(factoryBeanFor(mock(WebhookPublisher.class)).isSingleton()).isTrue();
    }

    @Test
    void buildsAProxyOfTheClientInterface() {
        CrmWebhooks proxy = factoryBeanFor(mock(WebhookPublisher.class)).getObject();

        assertThat(proxy).isInstanceOf(CrmWebhooks.class);
        assertThat(Proxy.isProxyClass(proxy.getClass())).isTrue();
    }

    @Test
    void proxyRoutesCallsToThePublisherForTheConfiguredDestination() {
        WebhookPublisher publisher = mock(WebhookPublisher.class);
        Event event = new Event();

        CrmWebhooks proxy = factoryBeanFor(publisher).getObject();
        proxy.contactCreated(event);

        verify(publisher).publish(eq("crm"), same(event));
    }
}
