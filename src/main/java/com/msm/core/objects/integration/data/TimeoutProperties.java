package com.msm.core.objects.integration.data;

import lombok.Data;

/**
 * Block {@code integration.connectors.<name>.timeout}. A connector with this block gets its own HTTP client
 * (see {@code ConnectorRequestClientRegistry}); without it the connector keeps the shared client, with no timeout.
 */
@Data
public class TimeoutProperties {
    /** Longest wait to open the connection; null: no limit. */
    private Long connectMs;
    /** Longest wait for the partner's answer; null: no limit. */
    private Long readMs;
}
