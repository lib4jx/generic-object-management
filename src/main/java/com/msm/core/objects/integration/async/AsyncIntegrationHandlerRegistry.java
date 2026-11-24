package com.msm.core.objects.integration.async;

import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The {@link AsyncIntegrationHandler} beans by connector name + {@link AsyncIntegrationHandler#action()}, the pair
 * stored on a task, for the sweeper and the action {@code resolve}. Built once every singleton exists
 * (no dependency cycle with the services' handlers); two handlers with the same pair fail the startup.
 */
public class AsyncIntegrationHandlerRegistry implements SmartInitializingSingleton {

    private static final String KEY_SEPARATOR = ":";

    private final Supplier<List<AsyncIntegrationHandler<?, ?>>> handlers;
    private Map<String, AsyncIntegrationHandler<?, ?>> byKey = Map.of();

    public AsyncIntegrationHandlerRegistry(Supplier<List<AsyncIntegrationHandler<?, ?>>> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, AsyncIntegrationHandler<?, ?>> map = new HashMap<>();
        for (AsyncIntegrationHandler<?, ?> handler : handlers.get()) {
            String key = key(handler.connector().getName(), handler.action());
            AsyncIntegrationHandler<?, ?> previous = map.put(key, handler);
            if (previous != null) {
                throw new IllegalStateException("Two AsyncIntegrationHandler for " + key + ": "
                        + previous.getClass().getName() + " and " + handler.getClass().getName() + "; override action()");
            }
        }
        byKey = Map.copyOf(map);
    }

    public Optional<AsyncIntegrationHandler<?, ?>> find(String connector, String action) {
        return Optional.ofNullable(byKey.get(key(connector, action)));
    }

    private String key(String connector, String action) {
        return connector + KEY_SEPARATOR + action;
    }
}
