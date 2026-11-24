package com.msm.core.objects.integration;

import com.msm.core.objects.config.IntegrationProperties;
import com.msm.core.objects.integration.factory.RestClientFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@link RequestClient} of each connector: a connector with a {@code timeout} block has its own client, built once
 * at startup; every other connector uses the shared {@code integrationRequestClient}, exactly as before 2.7.3.
 */
public class ConnectorRequestClientRegistry {

    private final RequestClient sharedClient;
    private final Map<String, RequestClient> ownClients;

    /** Every connector on the shared client. */
    public ConnectorRequestClientRegistry(RequestClient sharedClient) {
        this.sharedClient = sharedClient;
        this.ownClients = Map.of();
    }

    public ConnectorRequestClientRegistry(IntegrationProperties properties, RequestClient sharedClient,
                                          RestClientFactory restClientFactory) {
        this.sharedClient = sharedClient;
        Map<String, RequestClient> clients = new HashMap<>();
        properties.getConnectors().forEach((name, connector) -> {
            if (connector.getTimeout() != null) {
                clients.put(name, new DefaultRequestClient(restClientFactory.create(connector)));
            }
        });
        this.ownClients = Map.copyOf(clients);
    }

    public RequestClient get(String connectorName) {
        return connectorName == null ? sharedClient : ownClients.getOrDefault(connectorName, sharedClient);
    }

    public boolean hasOwnClient(String connectorName) {
        return connectorName != null && ownClients.containsKey(connectorName);
    }
}
