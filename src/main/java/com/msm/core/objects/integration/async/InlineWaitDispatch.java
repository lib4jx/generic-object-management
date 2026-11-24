package com.msm.core.objects.integration.async;

import com.msm.core.objects.exception.ObjectErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Async call with {@code async.inline-wait-ms > 0} (design §17.3). The task is inserted PROCESSING in its own
 * transaction, so other requests see the lock at once, and the partner is called at once on
 * {@code integrationTaskExecutor}; the action waits up to {@code inline-wait-ms}. Exactly one side handles the result
 * ({@link InlineDelivery}):
 * <ul>
 *   <li>result within the wait: the request, as the sync path. The callback runs in the caller's thread and
 *   transaction with {@code async = false} (it may throw to fail the action), the task is updated in that transaction,
 *   {@code afterSucceededCommit} runs after its commit. Caller's transaction rolled back: the task is closed in a new
 *   transaction (SUCCEEDED becomes UNCONFIRMED {@link IntegrationOutcome#ACTION_ROLLED_BACK}).</li>
 *   <li>no result within the wait: the action fails with {@code integration.task.processing} and the worker records the
 *   result in a new transaction, as with {@code inline-wait-ms: 0}.</li>
 * </ul>
 */
@Slf4j
class InlineWaitDispatch {

    private static final String ROLLED_BACK_AFTER_SUCCESS = "Action rolled back after partner success";

    private final IntegrationCallRunner runner;
    private final IntegrationTaskStore store;
    private final AsyncIntegrationWorker worker;
    private final TransactionTemplate newTransaction;
    private final AfterCommit afterCommit = new AfterCommit();

    InlineWaitDispatch(IntegrationCallRunner runner, IntegrationTaskStore store, AsyncIntegrationWorker worker,
                       PlatformTransactionManager transactionManager) {
        this.runner = runner;
        this.store = store;
        this.worker = worker;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Inserts and sends the task, then waits up to {@code waitMs}. Returns once the result was handled in the request;
     * a record with a task PROCESSING: {@code integration.task.in_progress}; no result in time:
     * {@code integration.task.processing}.
     */
    <P> void run(AsyncTaskRun<P> run, NewIntegrationTask row, long waitMs) {
        newTransaction.executeWithoutResult(status -> store.insert(row));
        CompletableFuture<ClassifiedOutcome> result;
        try {
            result = worker.sendNow(run);
        } catch (RejectedExecutionException e) {
            ClassifiedOutcome failed = worker.notSent(run, e);
            record(run.task(), failed);
            runner.deliver(run.handler(), inRequest(run.task()), failed);
            return;
        }
        InlineDelivery delivery = new InlineDelivery();
        result.thenAccept(outcome -> {
            if (!delivery.resultForRequest()) {
                worker.completeSafely(run, outcome);
            }
        });
        deliverInRequest(run, await(run, result, delivery, waitMs));
    }

    private ClassifiedOutcome await(AsyncTaskRun<?> run, CompletableFuture<ClassifiedOutcome> result,
                                    InlineDelivery delivery, long waitMs) {
        try {
            ClassifiedOutcome outcome = result.get(waitMs, TimeUnit.MILLISECONDS);
            // always true here: only this thread detaches
            delivery.resultForRequest();
            return outcome;
        } catch (TimeoutException e) {
            return resultOrDetach(run, result, delivery, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return resultOrDetach(run, result, delivery, e);
        } catch (ExecutionException e) {
            // sendNow completes normally; only a failure of its own fallback lands here: the sweeper closes the task
            throw new IllegalStateException("Integration task " + run.task().getTaskId() + " ended without a result", e.getCause());
        }
    }

    private ClassifiedOutcome resultOrDetach(AsyncTaskRun<?> run, CompletableFuture<ClassifiedOutcome> result,
                                             InlineDelivery delivery, Throwable cause) {
        if (delivery.detach()) {
            IntegrationTaskContext task = run.task();
            throw ObjectErrors.integrationTaskProcessing(task.getObjectName(), task.getRecordId(), task.getTaskId(), cause);
        }
        // the result arrived as the wait ended and its completion left it to the request: already done
        return result.join();
    }

    private <P> void deliverInRequest(AsyncTaskRun<P> run, ClassifiedOutcome outcome) {
        IntegrationTaskContext task = inRequest(run.task());
        closeOnRollback(task, outcome);
        if (store.finish(task.getTaskId(), outcome) == 0) {
            // only the sweeper closes a task PROCESSING, past its deadline: it already ran onUnconfirmed
            log.warn("Result {} of integration task {} dropped: the task is no longer PROCESSING", outcome.status(),
                    task.getTaskId());
            throw ObjectErrors.integrationTaskProcessing(task.getObjectName(), task.getRecordId(), task.getTaskId(), null);
        }
        runner.deliver(run.handler(), task, outcome);
        if (outcome.status() == IntegrationTaskStatus.SUCCEEDED) {
            afterCommit.run(() -> runner.afterSucceededCommit(run.handler(), task));
        }
    }

    /** The task written in the caller's transaction is undone by its rollback: write it again in a new one then. */
    private void closeOnRollback(IntegrationTaskContext task, ClassifiedOutcome outcome) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // no caller transaction: the result is written at once
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    closeAfterRollback(task, outcome);
                }
            }
        });
    }

    private void closeAfterRollback(IntegrationTaskContext task, ClassifiedOutcome outcome) {
        ClassifiedOutcome closing = outcome;
        if (outcome.status() == IntegrationTaskStatus.SUCCEEDED) {
            log.error("Integration task {}: {} succeeded at the partner but the action on {} {} rolled back; task UNCONFIRMED "
                    + "with the partner's answer, check the partner", task.getTaskId(), task.getAction(),
                    task.getObjectName(), task.getRecordId());
            closing = new ClassifiedOutcome(IntegrationTaskStatus.UNCONFIRMED, IntegrationOutcome.ACTION_ROLLED_BACK,
                    outcome.response(), IntegrationError.of(IntegrationOutcome.ACTION_ROLLED_BACK, ROLLED_BACK_AFTER_SUCCESS));
        }
        try {
            record(task, closing);
        } catch (RuntimeException e) {
            // after the caller's transaction: nothing above to report to; the sweeper closes the task at its deadline
            log.error("Integration task {}: writing {} after the action rolled back failed; UNCONFIRMED at its deadline",
                    task.getTaskId(), closing.status(), e);
        }
    }

    private void record(IntegrationTaskContext task, ClassifiedOutcome outcome) {
        newTransaction.executeWithoutResult(status -> store.finish(task.getTaskId(), outcome));
    }

    /** The callback runs in the action, as a direct call: it may throw to fail the action. */
    private IntegrationTaskContext inRequest(IntegrationTaskContext task) {
        return task.toBuilder().async(false).build();
    }
}
