package com.jinternals.webhooks.producer.client.registrar;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.client.WebhookClientInvocationHandler;
import com.jinternals.webhooks.producer.client.WebhookPublisher;
import java.lang.reflect.Proxy;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Creates the JDK dynamic proxy bean for one {@code @WebhookClient} interface (see SPEC.md §19). Spring
 * instantiates one of these per discovered client interface (registered by
 * {@link WebhookClientRegistrar}); its {@link #getObject()} returns a proxy whose calls route through
 * {@link WebhookClientInvocationHandler} to the shared {@link WebhookPublisher}.
 *
 * <p>The {@link WebhookPublisher} is looked up lazily via an autowired {@link ObjectProvider} so the
 * proxy beans can be registered before the publisher bean is defined, without ordering constraints.
 */
public class WebhookClientFactoryBean<T> implements FactoryBean<T> {

    private final Class<T> clientInterface;

    @Autowired
    private ObjectProvider<WebhookPublisher> publisher;

    public WebhookClientFactoryBean(Class<T> clientInterface) {
        this.clientInterface = clientInterface;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getObject() {
        String destination = clientInterface.getAnnotation(WebhookClient.class).destination();
        WebhookClientInvocationHandler handler = new WebhookClientInvocationHandler(destination, publisher.getObject());
        return (T) Proxy.newProxyInstance(clientInterface.getClassLoader(), new Class<?>[] {clientInterface}, handler);
    }

    @Override
    public Class<?> getObjectType() {
        return clientInterface;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
