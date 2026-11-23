package com.msm.core.objects.integration.async;

import com.msm.core.objects.integration.data.AsyncExecutorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Closes the tasks still PROCESSING past their {@code deadline_at} (instance stopped during the call, result lost):
 * UNCONFIRMED, then {@code onUnconfirmed} of the handler in the same transaction, one task per transaction. Runs on its
 * own thread every {@code integration.async-executor.sweep-interval-ms} (the service needs no {@code @EnableScheduling}),
 * without a user: the handler sees what the service's request context gives outside a request. Several instances can
 * run it: a task is closed by one only. A callback that fails rolls its task back to PROCESSING; it is tried again in
 * the next round, the other tasks go on.
 */
@Slf4j
public class AsyncIntegrationSweeper implements SmartLifecycle {

    private static final String DEADLINE_MESSAGE = "No result before the deadline (instance stopped or result lost)";

    private final IntegrationTaskStore store;
    private final AsyncIntegrationHandlerRegistry registry;
    private final IntegrationCallRunner runner;
    private final AsyncExecutorProperties properties;
    private final TransactionTemplate newTransaction;
    private final TaskContextFactory taskContexts = new TaskContextFactory();
    private ScheduledExecutorService executor;

    public AsyncIntegrationSweeper(IntegrationTaskStore store, AsyncIntegrationHandlerRegistry registry,
                                   IntegrationCallRunner runner, AsyncExecutorProperties properties,
                                   PlatformTransactionManager transactionManager) {
        this.store = store;
        this.registry = registry;
        this.runner = runner;
        this.properties = properties;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** One round; returns the number of tasks closed. */
    public int sweepOnce() {
        Set<UUID> failed = new HashSet<>();
        int closed = 0;
        while (true) {
            Optional<IntegrationTaskRow> next = newTransaction.execute(status -> {
                Optional<IntegrationTaskRow> row = store.expireNext(failed, DEADLINE_MESSAGE);
                row.ifPresent(task -> {
                    if (!notifyHandler(task)) {
                        failed.add(task.id());
                        status.setRollbackOnly();
                    }
                });
                return row;
            });
            if (next == null || next.isEmpty()) {
                return closed;
            }
            if (!failed.contains(next.get().id())) {
                closed++;
            }
        }
    }

    /** false: the callback failed (logged), the task must go back to PROCESSING. */
    private boolean notifyHandler(IntegrationTaskRow row) {
        Optional<AsyncIntegrationHandler<?, ?>> handler = registry.find(row.connector(), row.action());
        if (handler.isEmpty()) {
            log.warn("Integration task {} UNCONFIRMED at its deadline; no AsyncIntegrationHandler for {} / {}", row.id(),
                    row.connector(), row.action());
            return true;
        }
        try {
            AsyncTaskRun<?> run = taskContexts.runOf(handler.get(), row);
            runner.deliver(handler.get(), run.task(), ClassifiedOutcome.unconfirmed(IntegrationOutcome.DEADLINE_EXCEEDED,
                    IntegrationError.of(IntegrationOutcome.DEADLINE_EXCEEDED, DEADLINE_MESSAGE)));
            return true;
        } catch (RuntimeException e) {
            // one task must not stop the round: logged, retried next round
            log.error("onUnconfirmed of integration task {} ({} {}) failed; tried again next round", row.id(),
                    row.objectName(), row.recordId(), e);
            return false;
        }
    }

    private void runSafely() {
        try {
            int closed = sweepOnce();
            if (closed > 0) {
                log.info("{} integration task(s) UNCONFIRMED at their deadline", closed);
            }
        } catch (RuntimeException e) {
            log.error("Integration task sweep failed", e);
        }
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        long interval = properties.getSweepIntervalMs();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "integration-task-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runSafely, interval, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
