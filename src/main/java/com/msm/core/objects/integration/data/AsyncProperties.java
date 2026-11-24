package com.msm.core.objects.integration.data;

import lombok.Data;

/**
 * Block {@code integration.connectors.<name>.async}: how {@code AsyncIntegrationDispatcher} calls the connector.
 * Without the block, or with {@code enabled: false}, the call runs in the request, as before 2.7.3.
 */
@Data
public class AsyncProperties {
    /** true: the partner is called on {@code integrationTaskExecutor}, under an {@code integration_task}. */
    private boolean enabled = false;
    /** A task still PROCESSING after this delay is marked UNCONFIRMED by the sweeper. Required when enabled. */
    private Long deadlineMs;
    /**
     * With {@code enabled}: how long the action waits for the result before leaving it to the worker (since 2.7.4).
     * 0 (default): returns at once with the task PROCESSING, as 2.7.3. Greater than 0: a result within the wait is
     * handled in the action's transaction as a direct call; past it the action fails with
     * {@code integration.task.processing} and the worker records the result. Must be lower than {@code deadline-ms}.
     */
    private long inlineWaitMs = 0;
}
