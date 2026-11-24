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
}
