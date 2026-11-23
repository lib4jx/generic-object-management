package com.msm.core.objects.integration.async;

import com.msm.core.objects.integration.ConnectorRequestBuilder;
import com.msm.core.objects.integration.IntegrationClient;
import com.msm.core.objects.integration.context.HttpRequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Sends a task on {@code integrationTaskExecutor}, outside any transaction (with the security context of the user who
 * started it), then writes the result in a new transaction fenced on {@code attempt_id} and status PROCESSING, and calls
 * the handler's callback in that same transaction. A result that lost the race (the sweeper or a new sending got there
 * first) is dropped with a WARN; the answer stays in {@code integration_log} (correlation id = task id).
 */
@Slf4j
public class AsyncIntegrationWorker {

    private final Executor executor;
    private final IntegrationClient integrationClient;
    private final IntegrationCallRunner runner;
    private final IntegrationTaskStore store;
    private final TransactionTemplate newTransaction;

    public AsyncIntegrationWorker(Executor executor, IntegrationClient integrationClient, IntegrationCallRunner runner,
                                  IntegrationTaskStore store, PlatformTransactionManager transactionManager) {
        this.executor = executor;
        this.integrationClient = integrationClient;
        this.runner = runner;
        this.store = store;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Queues the sending. Executor full: the task is FAILED at once (nothing was sent). Never throws. */
    public <P> void submit(AsyncTaskRun<P> run) {
        try {
            executor.execute(() -> process(run));
        } catch (RejectedExecutionException e) {
            log.warn("integrationTaskExecutor full, task {} not sent: FAILED", run.task().getTaskId());
            completeSafely(run, ClassifiedOutcome.failed(IntegrationOutcome.EXECUTOR_FULL,
                    IntegrationError.of(IntegrationOutcome.EXECUTOR_FULL, "Too many calls in progress, not sent", e)));
        }
    }

    <P> void process(AsyncTaskRun<P> run) {
        ClassifiedOutcome outcome;
        try {
            ConnectorRequestBuilder client = integrationClient.connector(run.handler().connector())
                    .attribute(HttpRequestContext.ATTRIBUTE_ASYNC_TASK_ID, run.task().getTaskId());
            outcome = runner.call(run.handler(), run.task(), run.payload(), client);
        } catch (RuntimeException e) {
            // no client, or classify / onAlreadyDone of the handler failed: the task stays PROCESSING until the
            // sweeper closes it (top of the executor thread: nothing above to report to)
            log.error("Integration task {} could not classify its result; UNCONFIRMED at its deadline", run.task().getTaskId(), e);
            return;
        }
        completeSafely(run, outcome);
    }

    /** Writes the result and runs the callback in a new transaction; SUCCEEDED committed: {@code afterSucceededCommit}. */
    <P> void complete(AsyncTaskRun<P> run, ClassifiedOutcome outcome) {
        IntegrationTaskContext task = run.task();
        Boolean applied = newTransaction.execute(status -> {
            if (store.finish(task.getTaskId(), task.getAttemptId(), outcome) == 0) {
                log.warn("Result {} of integration task {} (attempt {}) dropped: the task is no longer PROCESSING under "
                        + "this attempt", outcome.status(), task.getTaskId(), task.getAttemptId());
                return Boolean.FALSE;
            }
            runner.deliver(run.handler(), task, outcome);
            return Boolean.TRUE;
        });
        if (Boolean.TRUE.equals(applied) && outcome.status() == IntegrationTaskStatus.SUCCEEDED) {
            runner.afterSucceededCommit(run.handler(), task);
        }
    }

    private <P> void completeSafely(AsyncTaskRun<P> run, ClassifiedOutcome outcome) {
        try {
            complete(run, outcome);
        } catch (RuntimeException e) {
            // the callback failed and its transaction rolled back: the sweeper closes the task at its deadline
            log.error("Integration task {}: recording {} failed; UNCONFIRMED at its deadline", run.task().getTaskId(),
                    outcome.status(), e);
        }
    }
}
