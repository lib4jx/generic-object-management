package com.msm.core.objects.integration.data;

import lombok.Data;

/** Block {@code integration.async-executor}: shared by every connector with {@code async.enabled}. */
@Data
public class AsyncExecutorProperties {
    public static final int DEFAULT_MAX_CONCURRENCY = 10;
    public static final int DEFAULT_QUEUE_CAPACITY = 100;
    public static final long DEFAULT_SWEEP_INTERVAL_MS = 60_000L;

    /** Threads calling partners at the same time. */
    private int maxConcurrency = DEFAULT_MAX_CONCURRENCY;
    /** Tasks waiting for a thread; a task beyond it is FAILED at once (not sent). */
    private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
    /** Delay between two rounds of the sweeper (tasks past their deadline become UNCONFIRMED). */
    private long sweepIntervalMs = DEFAULT_SWEEP_INTERVAL_MS;
}
