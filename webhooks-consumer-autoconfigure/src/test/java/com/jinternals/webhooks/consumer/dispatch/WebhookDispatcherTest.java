package com.jinternals.webhooks.consumer.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.consumer.annotations.WebhookHandler;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.consumer.web.error.NoWebhookHandlerException;
import com.jinternals.webhooks.core.annotations.WebhookType;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

class WebhookDispatcherTest {

    @WebhookType("a")
    static class EventA {}

    @WebhookType("b")
    static class EventB {}

    static class Handlers {
        String last;

        @WebhookHandler
        void onlyEvent(EventA e) {
            last = "onlyEvent";
        }

        @WebhookHandler
        void eventThenMeta(EventB e, WebhookMetadata m) {
            last = "eventThenMeta:" + m.type();
        }
    }

    static class MetaFirstHandler {
        String last;

        @WebhookHandler
        void metaThenEvent(WebhookMetadata m, EventA e) {
            last = "metaThenEvent";
        }
    }

    /** An interface-backed handler so it can be wrapped in a JDK dynamic proxy (which proxies interfaces). */
    interface HandlerService {
        void handle(EventA e);
    }

    static class HandlerServiceImpl implements HandlerService {
        String last;

        @Override
        @WebhookHandler
        public void handle(EventA e) {
            last = "handlerService";
        }
    }

    private WebhookMetadata meta(String type) {
        return new WebhookMetadata(type, "id", null, null, "/p", "ip", new Object(), Map.of(), Map.of());
    }

    @Test
    void dispatchesEventOnlySignature() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(EventOnlyConfig.class);
        WebhookDispatcher dispatcher = ctx.getBean(WebhookDispatcher.class);
        Handlers handlers = ctx.getBean(Handlers.class);

        dispatcher.dispatch(new EventA(), meta("a"));
        assertThat(handlers.last).isEqualTo("onlyEvent");

        dispatcher.dispatch(new EventB(), meta("b"));
        assertThat(handlers.last).isEqualTo("eventThenMeta:b");
    }

    @Test
    void dispatchesMetadataFirstSignature() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(MetaFirstConfig.class);
        WebhookDispatcher dispatcher = ctx.getBean(WebhookDispatcher.class);
        MetaFirstHandler handler = ctx.getBean(MetaFirstHandler.class);

        dispatcher.dispatch(new EventA(), meta("a"));
        assertThat(handler.last).isEqualTo("metaThenEvent");
    }

    @Test
    void throwsNoWebhookHandlerExceptionWhenNoHandlerRegistered() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(MetaFirstConfig.class);
        WebhookDispatcher dispatcher = ctx.getBean(WebhookDispatcher.class);
        // EventB has no handler in this context: a modelled, accepted type with no @WebhookHandler is a
        // config gap, not a success — it must throw so the delivery is not silently acknowledged.
        EventB event = new EventB();
        assertThatThrownBy(() -> dispatcher.dispatch(event, meta("b")))
                .isInstanceOf(NoWebhookHandlerException.class)
                .hasMessageContaining("b") // the type
                .hasMessageContaining(EventB.class.getName()); // the event class
    }

    @Test
    void doesNotEagerlyInitializeUnrelatedLazyBeansDuringScan() {
        // A drop-in library must not force the host's lazy, handler-less beans to initialize just to scan
        // them. The dispatcher resolves bean TYPES without instantiation and only fetches beans that actually
        // declare a @WebhookHandler. The @Lazy non-handler bean below must still be uninitialized after the
        // dispatcher's startup scan completes.
        UnrelatedLazyBean.INITIALIZED.set(false);
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(LazyBeanConfig.class);
        try {
            ctx.getBean(WebhookDispatcher.class); // triggers afterPropertiesSet scan
            assertThat(UnrelatedLazyBean.INITIALIZED)
                    .as("unrelated @Lazy non-handler bean must not be eagerly initialized by the scan")
                    .isFalse();
            // The handler bean WAS instantiated and its handler registered (dispatch works).
            Handlers handlers = ctx.getBean(Handlers.class);
            ctx.getBean(WebhookDispatcher.class).dispatch(new EventA(), meta("a"));
            assertThat(handlers.last).isEqualTo("onlyEvent");
        } finally {
            ctx.close();
        }
    }

    @Test
    void invokesAHandlerOnAProxiedBean() {
        // A host often proxies its service beans (transactions, security, @Retryable). The handler method is
        // discovered on the target class but must still be invocable on the proxy. With an AOP proxy around
        // the handler bean, dispatch must route through the proxy (advice runs) and reach the real handler.
        AtomicBoolean adviceRan = new AtomicBoolean(false);
        Handlers target = new Handlers();
        ProxyFactory pf = new ProxyFactory(target);
        pf.setProxyTargetClass(true); // CGLIB proxy of the concrete class
        pf.addAdvice((MethodInterceptor) invocation -> {
            adviceRan.set(true);
            return invocation.proceed();
        });
        Handlers proxied = (Handlers) pf.getProxy();

        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean("proxiedHandlers", Handlers.class, () -> proxied);
        ctx.registerBean("dispatcher", WebhookDispatcher.class, () -> new WebhookDispatcher(ctx));
        ctx.refresh();
        try {
            WebhookDispatcher dispatcher = ctx.getBean(WebhookDispatcher.class);

            dispatcher.dispatch(new EventA(), meta("a"));

            assertThat(adviceRan)
                    .as("dispatch must go through the proxy so advice runs")
                    .isTrue();
            assertThat(target.last).isEqualTo("onlyEvent"); // the real target handler ran
        } finally {
            ctx.close();
        }
    }

    @Test
    void invokesAHandlerOnAJdkDynamicProxyBean() {
        // The companion of the CGLIB test above, for the OTHER proxy strategy. A JDK dynamic proxy does NOT
        // subclass the target — it is a $ProxyN implementing the bean's interface — so two things that work
        // for CGLIB break naively here:
        //   1) the scan's type is $ProxyN, whose methods carry no target annotations (discovery would skip it);
        //   2) the @WebhookHandler Method discovered on the impl is not invocable on the $ProxyN instance.
        // The dispatcher must still discover the handler (via the proxy's target class) AND invoke it through
        // the proxy (so advice runs and reaches the real target).
        AtomicBoolean adviceRan = new AtomicBoolean(false);
        HandlerServiceImpl target = new HandlerServiceImpl();
        ProxyFactory pf = new ProxyFactory(target);
        pf.setInterfaces(HandlerService.class);
        pf.setProxyTargetClass(false); // JDK dynamic proxy of the interface
        pf.addAdvice((MethodInterceptor) invocation -> {
            adviceRan.set(true);
            return invocation.proceed();
        });
        HandlerService proxied = (HandlerService) pf.getProxy();
        assertThat(java.lang.reflect.Proxy.isProxyClass(proxied.getClass()))
                .as("fixture must actually be a JDK dynamic proxy")
                .isTrue();

        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean("proxiedService", HandlerService.class, () -> proxied);
        ctx.registerBean("dispatcher", WebhookDispatcher.class, () -> new WebhookDispatcher(ctx));
        ctx.refresh();
        try {
            WebhookDispatcher dispatcher = ctx.getBean(WebhookDispatcher.class);

            dispatcher.dispatch(new EventA(), meta("a"));

            assertThat(adviceRan)
                    .as("dispatch must go through the JDK proxy so advice runs")
                    .isTrue();
            assertThat(target.last).isEqualTo("handlerService"); // the real target handler ran
        } finally {
            ctx.close();
        }
    }

    @Test
    void skipsBeansWhoseTypeCannotBeResolvedWithoutInstantiation() {
        // A FactoryBean that reports no object type (getObjectType() == null) and is not yet instantiated
        // leaves getType(beanName) == null during the scan. The dispatcher must SKIP such a bean (it cannot
        // be classified without forcing creation) rather than NPE or eagerly instantiate it. The real
        // handler bean in the same context must still be found and registered.
        // getType(beanName) is contractually allowed to return null (the bean's type is not resolvable
        // without instantiating it). The dispatcher must SKIP such a bean rather than NPE on the null type,
        // while still registering the real handler bean in the same context. We drive the null deterministically
        // with a context that reports null for exactly one bean's type.
        try (NullTypeForOneBeanContext ctx = new NullTypeForOneBeanContext("unresolvable")) {
            ctx.registerBean("unresolvable", Object.class, Object::new);
            ctx.registerBean("handlers", Handlers.class, Handlers::new);
            ctx.registerBean("dispatcher", WebhookDispatcher.class, () -> new WebhookDispatcher(ctx));
            ctx.refresh();

            WebhookDispatcher dispatcher = ctx.getBean(WebhookDispatcher.class); // scan ran at refresh
            assertThat(ctx.nullTypeReturned)
                    .as("the null-type branch must actually have been exercised")
                    .isTrue();

            Handlers handlers = ctx.getBean(Handlers.class);
            dispatcher.dispatch(new EventA(), meta("a"));
            assertThat(handlers.last).isEqualTo("onlyEvent"); // the real handler was still registered
        }
    }

    @Test
    void rejectsZeroParamHandler() {
        assertThatThrownBy(() -> buildDispatcher(ZeroParam.class))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one @WebhookType event parameter")
                // the message must name the offending handler (invalid() output); kills the
                // mutant that blanks that prefix.
                .hasMessageContaining("@WebhookHandler ZeroParam#bad");
    }

    @Test
    void rejectsTooManyParams() {
        assertThatThrownBy(() -> buildDispatcher(ThreeParams.class))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one @WebhookType event parameter");
    }

    @Test
    void rejectsUnsupportedParamType() {
        assertThatThrownBy(() -> buildDispatcher(UnsupportedParam.class))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported parameter");
    }

    @Test
    void rejectsTwoTypeParams() {
        assertThatThrownBy(() -> buildDispatcher(TwoEvents.class))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than one @WebhookType parameter");
    }

    @Test
    void rejectsNoTypeParam() {
        assertThatThrownBy(() -> buildDispatcher(OnlyMetadata.class))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no @WebhookType event parameter");
    }

    @Test
    void rejectsDuplicateHandlersForSameType() {
        assertThatThrownBy(() -> buildDispatcher(DuplicateA.class, DuplicateB.class))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate @WebhookHandler");
    }

    @Test
    void rejectsBlankWebhookTypeParam() {
        assertThatThrownBy(() -> buildDispatcher(BlankTypeHandler.class))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank @WebhookType value")
                .hasMessageContaining(BlankTypeEvent.class.getName());
    }

    // --- helpers / configs ---

    private void buildDispatcher(Class<?>... beanClasses) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(DispatcherBeanConfig.class);
        for (Class<?> c : beanClasses) {
            ctx.register(c);
        }
        ctx.refresh();
        ctx.getBean(WebhookDispatcher.class);
    }

    // Lite @Bean configs (deliberately NOT @Configuration so the library's component scan in the
    // integration test does not sweep them up as application config).
    static class DispatcherBeanConfig {
        @Bean
        WebhookDispatcher dispatcher(org.springframework.context.ApplicationContext ctx) {
            return new WebhookDispatcher(ctx);
        }
    }

    static class EventOnlyConfig {
        @Bean
        Handlers handlers() {
            return new Handlers();
        }

        @Bean
        WebhookDispatcher dispatcher(org.springframework.context.ApplicationContext ctx) {
            return new WebhookDispatcher(ctx);
        }
    }

    static class MetaFirstConfig {
        @Bean
        MetaFirstHandler handler() {
            return new MetaFirstHandler();
        }

        @Bean
        WebhookDispatcher dispatcher(org.springframework.context.ApplicationContext ctx) {
            return new WebhookDispatcher(ctx);
        }
    }

    /** A lazy, handler-less bean that records whether it was ever instantiated. */
    static class UnrelatedLazyBean {
        static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

        UnrelatedLazyBean() {
            INITIALIZED.set(true);
        }
    }

    static class LazyBeanConfig {
        @Bean
        Handlers handlers() {
            return new Handlers();
        }

        @Bean
        @Lazy
        UnrelatedLazyBean unrelatedLazyBean() {
            return new UnrelatedLazyBean();
        }

        @Bean
        WebhookDispatcher dispatcher(org.springframework.context.ApplicationContext ctx) {
            return new WebhookDispatcher(ctx);
        }
    }

    /**
     * A context that reports {@code null} from {@link #getType(String)} for exactly one named bean, to
     * deterministically exercise the dispatcher's {@code type == null} skip branch (which models Spring's
     * contract that a bean's type may be unresolvable without instantiating it).
     */
    static class NullTypeForOneBeanContext extends AnnotationConfigApplicationContext {
        private final String nullTypeBeanName;
        boolean nullTypeReturned = false;

        NullTypeForOneBeanContext(String nullTypeBeanName) {
            this.nullTypeBeanName = nullTypeBeanName;
        }

        @Override
        public Class<?> getType(String name) {
            if (nullTypeBeanName.equals(name)) {
                nullTypeReturned = true;
                return null;
            }
            return super.getType(name);
        }
    }

    static class ZeroParam {
        @WebhookHandler
        void bad() {}
    }

    static class ThreeParams {
        @WebhookHandler
        void bad(EventA a, WebhookMetadata m, String x) {}
    }

    static class UnsupportedParam {
        @WebhookHandler
        void bad(String notAnEvent) {}
    }

    static class TwoEvents {
        @WebhookHandler
        void bad(EventA a, EventB b) {}
    }

    static class OnlyMetadata {
        @WebhookHandler
        void bad(WebhookMetadata m) {}
    }

    static class DuplicateA {
        @WebhookHandler
        void a(EventA e) {}
    }

    static class DuplicateB {
        @WebhookHandler
        void a2(EventA e) {}
    }

    @WebhookType("   ")
    static class BlankTypeEvent {}

    static class BlankTypeHandler {
        @WebhookHandler
        void bad(BlankTypeEvent e) {}
    }
}
