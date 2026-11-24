package com.msm.core.objects.integration.async;

import com.msm.core.objects.integration.ConnectorRequestBuilder;
import com.msm.core.objects.integration.IntegrationClient;
import com.msm.core.objects.integration.context.HttpRequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Sends a task on {@code integrationTaskExecutor}, outside any transaction (with the security context of the user who
 * started it), then writes the result in a new transaction fenced on status PROCESSING, and calls the handler's
 * callback in that same transaction. A result that lost the race (the sweeper got there first) is dropped with a WARN;
 * the answer stays in {@code integration_log} (correlation id = task id). With {@code inline-wait-ms > 0} the call is
 * sent at once ({@code sendNow}) and the worker records the result only when the action stopped waiting
 * ({@code InlineWaitDispatch}). A task that waited in the executor queue so long that its call (connect + read timeout)
 * could end past {@code deadline_at} is not sent: FAILED {@link IntegrationOutcome#EXPIRED_BEFORE_SEND}, so the partner
 * is never called after the sweeper released the record.
 */
@Slf4j
public class AsyncIntegrationWorker {

    private static final String EXPIRED_BEFORE_SEND_MESSAGE =
            "Not sent: waited too long for a free thread, the call could no longer end before the task deadline";

    private final Executor executor;
    private final IntegrationClient integrationClient;
    private final IntegrationCallRunner runner;
    private final IntegrationTaskStore store;
    private final TransactionTemplate newTransaction;
    private final Clock clock;

    public AsyncIntegrationWorker(Executor executor, IntegrationClient integrationClient, IntegrationCallRunner runner,
                                  IntegrationTaskStore store, PlatformTransactionManager transactionManager) {
        this(executor, integrationClient, runner, store, transactionManager, Clock.systemUTC());
    }

    AsyncIntegrationWorker(Executor executor, IntegrationClient integrationClient, IntegrationCallRunner runner,
                           IntegrationTaskStore store, PlatformTransactionManager transactionManager, Clock clock) {
        this.executor = executor;
        this.integrationClient = integrationClient;
        this.runner = runner;
        this.store = store;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Queues the sending. Executor full: the task is FAILED at once (nothing was sent). Never throws. */
    public <P> void submit(AsyncTaskRun<P> run) {
        try {
            executor.execute(() -> process(run));
        } catch (RejectedExecutionException e) {
            completeSafely(run, notSent(run, e));
        }
    }

    /**
     * Inline wait ({@code async.inline-wait-ms > 0}): sends at once, not after a commit, and gives the classified
     * result without recording it. The future completes normally: a result that cannot be classified (no client, the
     * handler's {@code classify} / {@code onAlreadyDone} failed) is UNCONFIRMED, the partner may have done the work.
     *
     * @throws RejectedExecutionException executor full, nothing sent ({@link #notSent})
     */
    <P> CompletableFuture<ClassifiedOutcome> sendNow(AsyncTaskRun<P> run) {
        return CompletableFuture.supplyAsync(() -> callPartner(run), executor)
                .exceptionally(error -> unclassified(run, error));
    }

    /** FAILED result of a task the full executor refused (nothing was sent). */
    <P> ClassifiedOutcome notSent(AsyncTaskRun<P> run, RejectedExecutionException error) {
        log.warn("integrationTaskExecutor full, task {} not sent: FAILED", run.task().getTaskId());
        return ClassifiedOutcome.failed(IntegrationOutcome.EXECUTOR_FULL,
                IntegrationError.of(IntegrationOutcome.EXECUTOR_FULL, "Too many calls in progress, not sent", error));
    }

    <P> void process(AsyncTaskRun<P> run) {
        ClassifiedOutcome outcome;
        try {
            outcome = callPartner(run);
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
            if (store.finish(task.getTaskId(), outcome) == 0) {
                log.warn("Result {} of integration task {} dropped: the task is no longer PROCESSING", outcome.status(),
                        task.getTaskId());
                return Boolean.FALSE;
            }
            runner.deliver(run.handler(), task, outcome);
            return Boolean.TRUE;
        });
        if (Boolean.TRUE.equals(applied) && outcome.status() == IntegrationTaskStatus.SUCCEEDED) {
            runner.afterSucceededCommit(run.handler(), task);
        }
    }

    /** {@link #complete}, its errors logged: the task stays PROCESSING and the sweeper closes it. Never throws. */
    <P> void completeSafely(AsyncTaskRun<P> run, ClassifiedOutcome outcome) {
        try {
            complete(run, outcome);
        } catch (RuntimeException e) {
            // the callback failed and its transaction rolled back: the sweeper closes the task at its deadline
            log.error("Integration task {}: recording {} failed; UNCONFIRMED at its deadline", run.task().getTaskId(),
                    outcome.status(), e);
        }
    }

    private <P> ClassifiedOutcome callPartner(AsyncTaskRun<P> run) {
        if (!endsBeforeDeadline(run)) {
            return expiredBeforeSend(run);
        }
        ConnectorRequestBuilder client = integrationClient.connector(run.handler().connector())
                .attribute(HttpRequestContext.ATTRIBUTE_ASYNC_TASK_ID, run.task().getTaskId());
        return runner.call(run.handler(), run.task(), run.payload(), client);
    }

    /** now + the call's longest time (connect + read timeout) is not past the task's {@code deadline_at}. */
    private boolean endsBeforeDeadline(AsyncTaskRun<?> run) {
        return !clock.instant().plusMillis(run.callTimeoutMs()).isAfter(run.deadlineAt());
    }

    /** FAILED result of a task taken too late to send: the partner is not called. */
    private ClassifiedOutcome expiredBeforeSend(AsyncTaskRun<?> run) {
        log.warn("Integration task {} not sent: a call of up to {} ms would end past its deadline {}; FAILED",
                run.task().getTaskId(), run.callTimeoutMs(), run.deadlineAt());
        return ClassifiedOutcome.failed(IntegrationOutcome.EXPIRED_BEFORE_SEND, IntegrationError.of(
                IntegrationOutcome.EXPIRED_BEFORE_SEND, EXPIRED_BEFORE_SEND_MESSAGE));
    }

    private <P> ClassifiedOutcome unclassified(AsyncTaskRun<P> run, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        log.error("Integration task {} could not classify its result: UNCONFIRMED", run.task().getTaskId(), cause);
        String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return ClassifiedOutcome.unconfirmed(IntegrationOutcome.UNKNOWN_ERROR,
                IntegrationError.of(IntegrationOutcome.UNKNOWN_ERROR, "Result could not be classified: " + message, cause));
    }
}
