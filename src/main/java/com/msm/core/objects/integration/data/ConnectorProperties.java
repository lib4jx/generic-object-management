package com.msm.core.objects.integration.data;

import com.msm.core.objects.integration.data.retry.RetryProperties;
import lombok.Data;

@Data
public class ConnectorProperties {
    private String baseUrl;
    private AuthProviderProperties auth;
    private RetryProperties retry = new RetryProperties();
    /** Null: shared client, no timeout. */
    private TimeoutProperties timeout;
    /** Null: calls run in the request. */
    private AsyncProperties async;

    public boolean asyncEnabled() {
        return async != null && async.isEnabled();
    }

    /** {@code timeout.read-ms}, or null without a read timeout. */
    public Long readTimeoutMs() {
        return timeout == null ? null : timeout.getReadMs();
    }

    /**
     * Longest a call may take: {@code timeout.connect-ms} (0 when not set) + {@code timeout.read-ms}; null without a
     * read timeout.
     */
    public Long callTimeoutMs() {
        Long readMs = readTimeoutMs();
        if (readMs == null) {
            return null;
        }
        Long connectMs = timeout.getConnectMs();
        return connectMs == null ? readMs : connectMs + readMs;
    }
}
