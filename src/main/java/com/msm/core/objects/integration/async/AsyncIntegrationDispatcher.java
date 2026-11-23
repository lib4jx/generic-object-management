package com.msm.core.objects.integration.async;

import com.msm.core.action.context.ActionContext;
import com.msm.core.objects.config.IntegrationProperties;
import com.msm.core.objects.integration.IntegrationClient;
import com.msm.core.objects.integration.data.ConnectorProperties;
import com.msm.core.security.RequestContextHolder;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Called by a {@code @Handler} exactly where it used to call the partner: {@code dispatcher.dispatch(ctx, handler, input)}.
 * <ul>
 *   <li>Connector without {@code async.enabled} (default): prepare, call, classify and callback run at once in the
 *   caller's thread and transaction, as a direct call did; no task row. Errors are the ones the handler throws in its
 *   callbacks. {@code afterSucceededCommit} runs after the caller's transaction commits. Returns null.</li>
 *   <li>{@code async.enabled}: prepare, then a task PROCESSING is inserted in the caller's transaction and the call is
 *   queued once it commits (never on rollback). A record with a task PROCESSING: {@code integration.task.in_progress}.
 *   Returns the task to put in the action's response.</li>
 * </ul>
 */
@RequiredArgsConstructor
public class AsyncIntegrationDispatcher {

    private final IntegrationProperties properties;
    private final IntegrationClient integrationClient;
    private final IntegrationCallRunner runner;
    private final IntegrationTaskStore store;
    private final AsyncIntegrationWorker worker;
    private final AfterCommit afterCommit = new AfterCommit();
    private final IntegrationTaskJson json = new IntegrationTaskJson();

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
                .attemptCount(1)
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
        Instant startedAt = Instant.now();
        Instant deadlineAt = startedAt.plus(Duration.ofMillis(connector.getAsync().getDeadlineMs()));
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        store.insert(new NewIntegrationTask(taskId, handler.connector().getName(), actionContext.getResource(), recordId,
                handler.action(), attemptId, json.write(prepared.payload()), json.write(prepared.context()), startedAt,
                deadlineAt, RequestContextHolder.getRequestContext().getUsername()));
        IntegrationTaskContext task = IntegrationTaskContext.builder()
                .taskId(taskId)
                .connector(handler.connector().getName())
                .objectName(actionContext.getResource())
                .recordId(recordId)
                .action(handler.action())
                .attemptId(attemptId)
                .attemptCount(1)
                .payload(prepared.payload())
                .context(prepared.context())
                .async(true)
                .build();
        AsyncTaskRun<P> run = new AsyncTaskRun<>(handler, task, prepared.payload());
        afterCommit.run(() -> worker.submit(run));
        return new IntegrationTaskView(taskId, IntegrationTaskStatus.PROCESSING, deadlineAt);
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
