package com.msm.core.objects.integration.data;

import lombok.Data;

/**
 * Block {@code integration.connectors.<name>.async}: how {@code AsyncIntegrationDispatcher} calls the connector.
 * Without the block, or with {@code enabled: false}, the call runs in the request, as before 2.8.0.
 */
@Data
public class AsyncProperties {
    /** true: the action returns at once and the partner is called on {@code integrationTaskExecutor}. */
    private boolean enabled = false;
    /** A task still PROCESSING after this delay is marked UNCONFIRMED by the sweeper. Required when enabled or idempotent. */
    private Long deadlineMs;
    /** Sending the same request again creates nothing twice at the partner: allows the action {@code retry}. */
    private boolean idempotent = false;
}
