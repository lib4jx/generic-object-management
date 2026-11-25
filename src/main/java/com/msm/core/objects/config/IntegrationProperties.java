package com.msm.core.objects.config;

import com.msm.core.objects.integration.auth.enums.AuthProviderEnum;
import com.msm.core.objects.integration.auth.enums.OAuth2ClientAuthMethod;
import com.msm.core.objects.integration.data.AsyncExecutorProperties;
import com.msm.core.objects.integration.data.AsyncProperties;
import com.msm.core.objects.integration.data.AuthProviderProperties;
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
        validateOAuth2ClientAuth(name, connector.getAuth());
        TimeoutProperties timeout = connector.getTimeout();
        if (timeout != null) {
            requirePositive(name, "timeout.connect-ms", timeout.getConnectMs());
            requirePositive(name, "timeout.read-ms", timeout.getReadMs());
        }
        AsyncProperties async = connector.getAsync();
        if (async == null || !async.isEnabled()) {
            return;
        }
        Long deadlineMs = async.getDeadlineMs();
        if (deadlineMs == null || deadlineMs <= 0) {
            throw new IllegalStateException(CONNECTOR_PREFIX + name
                    + ".async.deadline-ms is required (> 0) when async.enabled is true");
        }
        Long readMs = connector.readTimeoutMs();
        if (readMs == null) {
            throw new IllegalStateException(CONNECTOR_PREFIX + name
                    + ".timeout.read-ms is required when async.enabled is true: without it a call may never end");
        }
        // the worker does not send a call that could end past the deadline: deadline-ms must leave room for one call
        Long callTimeoutMs = connector.callTimeoutMs();
        if (deadlineMs <= callTimeoutMs) {
            throw new IllegalStateException(CONNECTOR_PREFIX + name + ".async.deadline-ms (" + deadlineMs
                    + ") must be greater than timeout.connect-ms + timeout.read-ms (" + callTimeoutMs + ")");
        }
        validateInlineWait(name, async.getInlineWaitMs(), deadlineMs);
        requirePositive(name, "async.request-budget-ms", async.getRequestBudgetMs());
    }

    /** {@code client-auth-method} of an {@code oauth2-credentials} connector (2.7.9): a known value, a form body with basic. */
    private void validateOAuth2ClientAuth(String name, AuthProviderProperties auth) {
        if (auth == null || auth.getProperties() == null
                || !AuthProviderEnum.OAUTH2_CLIENT_CREDENTIALS.getName().equals(auth.getProvider())) {
            return;
        }
        final Object rawMethod = auth.getProperties().get("clientAuthMethod");
        final OAuth2ClientAuthMethod method;
        try {
            method = OAuth2ClientAuthMethod.fromValue(rawMethod == null ? null : rawMethod.toString());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(CONNECTOR_PREFIX + name + ".auth.properties.client-auth-method: " + ex.getMessage(), ex);
        }
        final boolean strictJson = Boolean.parseBoolean(String.valueOf(auth.getProperties().get("strictJsonMode")));
        if (method == OAuth2ClientAuthMethod.CLIENT_SECRET_BASIC && strictJson) {
            throw new IllegalStateException(CONNECTOR_PREFIX + name + ".auth.properties: client-auth-method "
                    + OAuth2ClientAuthMethod.CLIENT_SECRET_BASIC.getValue()
                    + " sends a form body, it cannot be combined with strict-json-mode: true");
        }
    }

    private void validateInlineWait(String name, long inlineWaitMs, long deadlineMs) {
        if (inlineWaitMs < 0) {
            throw new IllegalStateException(CONNECTOR_PREFIX + name + ".async.inline-wait-ms must be >= 0, got " + inlineWaitMs);
        }
        if (inlineWaitMs >= deadlineMs) {
            throw new IllegalStateException(CONNECTOR_PREFIX + name + ".async.inline-wait-ms (" + inlineWaitMs
                    + ") must be lower than async.deadline-ms (" + deadlineMs + ")");
        }
    }

    private void requirePositive(String connectorName, String key, Long value) {
        if (value != null && value <= 0) {
            throw new IllegalStateException(CONNECTOR_PREFIX + connectorName + "." + key + " must be > 0, got " + value);
        }
    }
}
