package com.jinternals.webhooks.producer.client.registrar;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.client.WebhookClientInvocationHandler;
import com.jinternals.webhooks.producer.client.WebhookPublisher;
import java.lang.reflect.Proxy;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Creates the JDK dynamic proxy bean for one {@code @WebhookClient} interface (see SPEC.md §19). Spring
 * instantiates one of these per discovered client interface (registered by
 * {@link WebhookClientRegistrar}); its {@link #getObject()} returns a proxy whose calls route through
 * {@link WebhookClientInvocationHandler} to the shared {@link WebhookPublisher}.
 *
 * <p>The {@link WebhookPublisher} is looked up lazily via {@link BeanFactoryAware} + {@link ObjectProvider}
 * so proxy beans can be registered before the publisher bean is defined, without ordering constraints or
 * reflective field injection.
 */
public class WebhookClientFactoryBean<T> implements FactoryBean<T>, BeanFactoryAware {

    private final Class<T> clientInterface;
    private @Nullable ObjectProvider<WebhookPublisher> publisher;

    public WebhookClientFactoryBean(Class<T> clientInterface) {
        this.clientInterface = clientInterface;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.publisher = beanFactory.getBeanProvider(WebhookPublisher.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getObject() {
        String destination = Objects.requireNonNull(clientInterface.getAnnotation(WebhookClient.class))
                .destination();
        WebhookPublisher resolvedPublisher = Objects.requireNonNull(publisher, "BeanFactory was not injected")
                .getObject();
        WebhookClientInvocationHandler handler = new WebhookClientInvocationHandler(destination, resolvedPublisher);
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
