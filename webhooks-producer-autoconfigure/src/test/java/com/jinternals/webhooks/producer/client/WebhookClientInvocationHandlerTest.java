package com.jinternals.webhooks.producer.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookClientInvocationHandlerTest {

    private WebhookPublisher publisher;
    private WebhookClientInvocationHandler handler;

    interface SampleClient {
        void send(Object event);
    }

    interface DefaultMethodClient {
        void send(Object event);

        default String describe() {
            return "described";
        }
    }

    @BeforeEach
    void setUp() {
        publisher = mock(WebhookPublisher.class);
        handler = new WebhookClientInvocationHandler("crm", publisher);
    }

    @Test
    void publishesSingleArgEventToDestination() throws Throwable {
        Method send = SampleClient.class.getMethod("send", Object.class);
        Object event = new Object();

        Object result = handler.invoke(new Object(), send, new Object[] {event});

        assertThat(result).isNull();
        verify(publisher).publish(eq("crm"), any());
    }

    @Test
    void defaultMethodRunsItsOwnBodyAndIsNotPublished() throws Throwable {
        // A default helper method must run its own body via InvocationHandler.invokeDefault, not be
        // mis-published. invokeDefault requires a real proxy backed by this handler.
        DefaultMethodClient proxy = (DefaultMethodClient) Proxy.newProxyInstance(
                DefaultMethodClient.class.getClassLoader(), new Class<?>[] {DefaultMethodClient.class}, handler);

        Method describe = DefaultMethodClient.class.getMethod("describe");
        Object result = handler.invoke(proxy, describe, null);

        assertThat(result).isEqualTo("described");
        verifyNoInteractions(publisher);
    }

    @Test
    void equalsIsIdentityBased() throws Throwable {
        Method equals = Object.class.getMethod("equals", Object.class);
        Object proxy = new Object();

        assertThat((Boolean) handler.invoke(proxy, equals, new Object[] {proxy}))
                .isTrue();
        assertThat((Boolean) handler.invoke(proxy, equals, new Object[] {new Object()}))
                .isFalse();
    }

    @Test
    void hashCodeIsIdentityHash() throws Throwable {
        Method hashCode = Object.class.getMethod("hashCode");
        Object proxy = new Object();

        assertThat((Integer) handler.invoke(proxy, hashCode, null)).isEqualTo(System.identityHashCode(proxy));
    }

    @Test
    void toStringDescribesDestination() throws Throwable {
        Method toString = Object.class.getMethod("toString");
        Object result = handler.invoke(new Object(), toString, null);

        assertThat(result).isEqualTo("WebhookClient[destination=crm]");
    }

    @Test
    void defaultMethodOnPackagePrivateInterfaceInDifferentPackageRunsWithoutIllegalAccess() throws Throwable {
        Class<?> pkgPrivateIface =
                com.jinternals.webhooks.producer.fixtures.defaultpkg.PackagePrivateDefaultClientFixture
                        .clientInterface();
        Object proxy =
                Proxy.newProxyInstance(pkgPrivateIface.getClassLoader(), new Class<?>[] {pkgPrivateIface}, handler);
        Method helper = pkgPrivateIface.getDeclaredMethod("helper", String.class);

        Object result = handler.invoke(proxy, helper, new Object[] {"crm"});

        assertThat(result).isEqualTo("crm:ok");
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsUnexpectedObjectMethod() throws Exception {
        // A proxy only routes equals/hashCode/toString; invoking the handler with another Object
        // method (getClass) documents and covers the defensive default branch.
        Method getClass = Object.class.getMethod("getClass");
        assertThatThrownBy(() -> handler.invoke(new Object(), getClass, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected Object method");
    }
}
