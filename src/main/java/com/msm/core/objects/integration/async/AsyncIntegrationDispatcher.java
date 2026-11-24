package com.msm.core.objects.integration.async;

import com.msm.core.action.context.ActionContext;
import com.msm.core.objects.config.IntegrationProperties;
import com.msm.core.objects.exception.ObjectErrors;
import com.msm.core.objects.integration.IntegrationClient;
import com.msm.core.objects.integration.data.AsyncProperties;
import com.msm.core.objects.integration.data.ConnectorProperties;
import com.msm.core.security.RequestContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Called by a {@code @Handler} exactly where it used to call the partner: {@code dispatcher.dispatch(ctx, handler, input)}.
 * <ul>
 *   <li>Connector without {@code async.enabled} (default): prepare, call, classify and callback run at once in the
 *   caller's thread and transaction, as a direct call did; no task row. Errors are the ones the handler throws in its
 *   callbacks. {@code afterSucceededCommit} runs after the caller's transaction commits. Returns null.</li>
 *   <li>{@code async.enabled} with {@code inline-wait-ms: 0}: prepare, then a task PROCESSING is inserted in the
 *   caller's transaction and the call is queued once it commits (never on rollback). Returns the task to put in the
 *   action's response.</li>
 *   <li>{@code async.enabled} with {@code inline-wait-ms > 0}: prepare, the task is inserted in its own transaction
 *   and sent at once; a result within the wait is handled as the sync path and null is returned, else
 *   {@code integration.task.processing} and the worker records the result ({@code InlineWaitDispatch}). With
 *   {@code request-budget-ms} the wait keeps within the request's budget, and a request without time left fails with
 *   {@code integration.task.request_budget_exceeded} before any task or call ({@link RequestBudget}).</li>
 * </ul>
 * In both async modes a record with a task PROCESSING gets {@code integration.task.in_progress}.
 */
public class AsyncIntegrationDispatcher {

    private final IntegrationProperties properties;
    private final IntegrationClient integrationClient;
    private final IntegrationCallRunner runner;
    private final IntegrationTaskStore store;
    private final AsyncIntegrationWorker worker;
    private final InlineWaitDispatch inlineWait;
    private final RequestBudget requestBudget;
    private final AfterCommit afterCommit = new AfterCommit();
    private final IntegrationTaskJson json = new IntegrationTaskJson();

    public AsyncIntegrationDispatcher(IntegrationProperties properties, IntegrationClient integrationClient,
                                      IntegrationCallRunner runner, IntegrationTaskStore store,
                                      AsyncIntegrationWorker worker, PlatformTransactionManager transactionManager) {
        this(properties, integrationClient, runner, store, worker, transactionManager, new RequestBudget());
    }

    AsyncIntegrationDispatcher(IntegrationProperties properties, IntegrationClient integrationClient,
                               IntegrationCallRunner runner, IntegrationTaskStore store, AsyncIntegrationWorker worker,
                               PlatformTransactionManager transactionManager, RequestBudget requestBudget) {
        this.properties = properties;
        this.integrationClient = integrationClient;
        this.runner = runner;
        this.store = store;
        this.worker = worker;
        this.inlineWait = new InlineWaitDispatch(runner, store, worker, transactionManager);
        this.requestBudget = requestBudget;
    }

    public <I, P> IntegrationTaskView dispatch(ActionContext<?> actionContext, AsyncIntegrationHandler<I, P> handler, I input) {
        PreparedCall<P> prepared = handler.prepare(actionContext, input);
        ConnectorProperties connector = connector(handler);
        if (!connector.asyncEnabled()) {
            runInRequest(actionContext, handler, prepared);
            return null;
        }
        return startTask(actionContext, handler, prepared, connector);
    }

    private <P> void runInRequest(ActionContext<?> actionContext, AsyncIntegrationHandler<?, P> handler, PreparedCall<P> prepared) {
        IntegrationTaskContext task = IntegrationTaskContext.builder()
                .connector(handler.connector().getName())
                .objectName(actionContext.getResource())
                .recordId(recordId(actionContext, prepared))
                .action(handler.action())
                .payload(prepared.payload())
                .context(prepared.context())
                .async(false)
                .build();
        ClassifiedOutcome outcome = runner.call(handler, task, prepared.payload(), integrationClient.connector(handler.connector()));
        runner.deliver(handler, task, outcome);
        if (outcome.status() == IntegrationTaskStatus.SUCCEEDED) {
            afterCommit.run(() -> runner.afterSucceededCommit(handler, task));
        }
    }

    private <P> IntegrationTaskView startTask(ActionContext<?> actionContext, AsyncIntegrationHandler<?, P> handler,
                                              PreparedCall<P> prepared, ConnectorProperties connector) {
        UUID recordId = recordId(actionContext, prepared);
        if (recordId == null) {
            throw new IllegalStateException("Async call " + handler.action() + " on " + actionContext.getResource()
                    + " needs a record id: objectId of the action or PreparedCall.forRecord");
        }
        long inlineWaitMs = inlineWaitMs(connector.getAsync(), actionContext.getResource(), recordId);
        Instant startedAt = Instant.now();
        Instant deadlineAt = startedAt.plus(Duration.ofMillis(connector.getAsync().getDeadlineMs()));
        UUID taskId = UUID.randomUUID();
        NewIntegrationTask row = new NewIntegrationTask(taskId, handler.connector().getName(), actionContext.getResource(),
                recordId, handler.action(), json.write(prepared.context()), startedAt, deadlineAt,
                RequestContextHolder.getRequestContext().getUsername());
        IntegrationTaskContext task = IntegrationTaskContext.builder()
                .taskId(taskId)
                .connector(handler.connector().getName())
                .objectName(actionContext.getResource())
                .recordId(recordId)
                .action(handler.action())
                .payload(prepared.payload())
                .context(prepared.context())
                .async(true)
                .build();
        // no read timeout (only outside the validated configuration): only a deadline already passed stops the sending
        long callTimeoutMs = Objects.requireNonNullElse(connector.callTimeoutMs(), 0L);
        AsyncTaskRun<P> run = new AsyncTaskRun<>(handler, task, prepared.payload(), deadlineAt, callTimeoutMs);
        if (inlineWaitMs > 0) {
            inlineWait.run(run, row, inlineWaitMs);
            return null;
        }
        store.insert(row);
        afterCommit.run(() -> worker.submit(run));
        return new IntegrationTaskView(taskId, IntegrationTaskStatus.PROCESSING, deadlineAt);
    }

    /**
     * {@code inline-wait-ms}, within what is left of {@code request-budget-ms}; none left: nothing is sent and no task
     * is created ({@code integration.task.request_budget_exceeded}).
     */
    private long inlineWaitMs(AsyncProperties async, String objectName, UUID recordId) {
        long inlineWaitMs = async.getInlineWaitMs();
        Long budgetMs = async.getRequestBudgetMs();
        if (inlineWaitMs <= 0 || budgetMs == null) {
            return inlineWaitMs;
        }
        Long elapsedMs = requestBudget.elapsedMs();
        long waitMs = requestBudget.waitMs(inlineWaitMs, budgetMs, elapsedMs);
        if (waitMs <= 0) {
            throw ObjectErrors.integrationTaskRequestBudgetExceeded(objectName, recordId, elapsedMs, budgetMs);
        }
        return waitMs;
    }

    private ConnectorProperties connector(AsyncIntegrationHandler<?, ?> handler) {
        String name = handler.connector().getName();
        ConnectorProperties connector = properties.getConnectors().get(name);
        if (connector == null) {
            throw new IllegalStateException("No configuration integration.connectors." + name);
        }
        return connector;
    }

    private UUID recordId(ActionContext<?> actionContext, PreparedCall<?> prepared) {
        if (prepared.recordId() != null) {
            return prepared.recordId();
        }
        return actionContext.getObjectId() == null ? null : actionContext.getObjectIdAs(UUID.class);
    }
}
