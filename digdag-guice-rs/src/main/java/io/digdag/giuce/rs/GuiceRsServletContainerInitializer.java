package io.digdag.guice.rs;

import java.util.Map;
import java.util.Set;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRegistration;
import javax.servlet.annotation.HandlesTypes;

@HandlesTypes(GuiceRsBootstrap.class)
public class GuiceRsServletContainerInitializer
        implements ServletContainerInitializer
{
    @Override
    @SuppressWarnings("unchecked")
    public void onStartup(Set<Class<?>> classes, ServletContext ctx)
    {
        ctx.setAttribute(GuiceRsServletContainerInitializer.class.getName(), this);

        for (Class<?> bootstrap : classes) {
            if (GuiceRsBootstrap.class.isAssignableFrom(bootstrap)) {
                processBootstrap((Class<GuiceRsBootstrap>) bootstrap, ctx);
            }
            else {
                // TODO show warnings
            }
        }
    }

    private void processBootstrap(final Class<GuiceRsBootstrap> bootstrap, final ServletContext context)
    {
        final Injector injector = Guice.createInjector((binder) -> {
                binder.bind(GuiceRsBootstrap.class).to(bootstrap);
                binder.bind(ServletContext.class).toInstance(context);
            })
            .getInstance(GuiceRsBootstrap.class)
            .initialize();

        if (injector instanceof AutoCloseable) {
            context.addListener(new CloseableInjectorDestroyListener((AutoCloseable) injector));
        }

        injector.findBindingsByType(TypeLiteral.get(GuiceRsServletInitializer.class))
            .stream()
            .map(binding -> binding.getProvider().get())
            .forEach(initializer -> initializer.register(injector, context));
    }

    private static class CloseableInjectorDestroyListener
            implements ServletContextListener
    {
        private final AutoCloseable closeable;

        public CloseableInjectorDestroyListener(AutoCloseable closeable)
        {
            this.closeable = closeable;
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
            try {
                closeable.close();
            }
            catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }

        @Override
        public void contextInitialized(ServletContextEvent sce)
        { }
    }
}
