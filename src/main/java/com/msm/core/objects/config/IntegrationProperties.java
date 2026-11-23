package com.msm.core.objects.config;

import com.msm.core.objects.integration.data.AsyncExecutorProperties;
import com.msm.core.objects.integration.data.AsyncProperties;
import com.msm.core.objects.integration.data.ConnectorProperties;
import com.msm.core.objects.integration.data.TimeoutProperties;
import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "integration")
@Data
public class IntegrationProperties implements InitializingBean {
    private static final String CONNECTOR_PREFIX = "integration.connectors.";

    private Map<String, ConnectorProperties> connectors = new HashMap<>();
    private AsyncExecutorProperties asyncExecutor = new AsyncExecutorProperties();

    /** At least one connector has {@code async.enabled: true}: the guard, the sweeper and the task table are in use. */
    public boolean hasAsyncConnector() {
        return connectors.values().stream().anyMatch(ConnectorProperties::asyncEnabled);
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /** Fails the startup on a timeout or async block that cannot work, with the key to fix in the message. */
    public void validate() {
        connectors.forEach(this::validateConnector);
    }

    private void validateConnector(String name, ConnectorProperties connector) {
        TimeoutProperties timeout = connector.getTimeout();
        if (timeout != null) {
            requirePositive(name, "timeout.connect-ms", timeout.getConnectMs());
            requirePositive(name, "timeout.read-ms", timeout.getReadMs());
        }
        AsyncProperties async = connector.getAsync();
        if (async == null || !(async.isEnabled() || async.isIdempotent())) {
            return;
        }
        Long deadlineMs = async.getDeadlineMs();
        if (deadlineMs == null || deadlineMs <= 0) {
            throw new IllegalStateException(CONNECTOR_PREFIX + name
                    + ".async.deadline-ms is required (> 0) when async.enabled or async.idempotent is true");
        }
        Long readMs = connector.readTimeoutMs();
        if (readMs != null && deadlineMs <= readMs) {
            throw new IllegalStateException(CONNECTOR_PREFIX + name + ".async.deadline-ms (" + deadlineMs
                    + ") must be greater than timeout.read-ms (" + readMs + ")");
        }
    }

    private void requirePositive(String connectorName, String key, Long value) {
        if (value != null && value <= 0) {
            throw new IllegalStateException(CONNECTOR_PREFIX + connectorName + "." + key + " must be > 0, got " + value);
        }
    }
}
