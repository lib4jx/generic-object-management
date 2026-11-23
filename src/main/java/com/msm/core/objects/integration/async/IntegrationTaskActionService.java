package com.msm.core.objects.integration.async;

import com.msm.core.commons.Utils;
import com.msm.core.exceptions.CommonErrors;
import com.msm.core.metadata.typesafe.DataRecord;
import com.msm.core.objects.audit.changelog.ManualChangeLog;
import com.msm.core.objects.config.IntegrationProperties;
import com.msm.core.objects.entity.metadata.IntegrationTaskMeta;
import com.msm.core.objects.exception.ObjectErrors;
import com.msm.core.objects.integration.data.ConnectorProperties;
import com.msm.core.security.RequestContextHolder;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Actions on an UNCONFIRMED task (design §6), in the transaction of the action route:
 * <ul>
 *   <li>{@code retry}: sends the stored request again as is, same task, new attempt; only for a connector with
 *   {@code async.idempotent}, else {@code integration.task.unconfirmed};</li>
 *   <li>{@code resolve}: closes the task by hand, {@code status} SUCCEEDED (with {@code data}, the answer the handler
 *   expects) or FAILED, {@code reason} required; the handler's callback runs as for a call result.</li>
 * </ul>
 */
@RequiredArgsConstructor
public class IntegrationTaskActionService {

    public static final String STATUS_PARAM = "status";
    public static final String REASON_PARAM = "reason";
    public static final String DATA_PARAM = "data";
    private static final Set<IntegrationTaskStatus> RESOLVE_STATUSES = Set.of(IntegrationTaskStatus.SUCCEEDED,
            IntegrationTaskStatus.FAILED);

    private final IntegrationTaskStore store;
    private final AsyncIntegrationHandlerRegistry registry;
    private final AsyncIntegrationWorker worker;
    private final IntegrationCallRunner runner;
    private final IntegrationProperties properties;
    private final ManualChangeLog manualChangeLog;
    private final AfterCommit afterCommit = new AfterCommit();
    private final TaskContextFactory taskContexts = new TaskContextFactory();

    public Map<String, Object> retry(UUID taskId) {
        IntegrationTaskRow row = unconfirmedTask(taskId);
        ConnectorProperties connector = properties.getConnectors().get(row.connector());
        if (connector == null || !connector.idempotent()) {
            throw ObjectErrors.integrationTaskUnconfirmed(taskId);
        }
        AsyncIntegrationHandler<?, ?> handler = handlerOf(row);
        UUID attemptId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        Instant deadlineAt = startedAt.plus(Duration.ofMillis(connector.getAsync().getDeadlineMs()));
        if (store.restart(taskId, attemptId, startedAt, deadlineAt, row.objectName(), row.recordId()) == 0) {
            throw ObjectErrors.integrationTaskInvalidStatus(taskId, currentStatus(taskId));
        }
        logChange(row, IntegrationTaskStatus.PROCESSING, Map.of(IntegrationTaskMeta.ATTEMPT_COUNT.getFieldName(), row.attemptCount() + 1));
        IntegrationTaskRow restarted = new IntegrationTaskRow(row.id(), row.connector(), row.objectName(), row.recordId(),
                row.action(), IntegrationTaskStatus.PROCESSING, null, attemptId, row.attemptCount() + 1, row.request(),
                row.context(), null, null, null, null, null, startedAt, deadlineAt, null, row.createdBy(), row.createdAt(),
                startedAt, row.version() + 1);
        AsyncTaskRun<?> run = taskContexts.runOf(handler, restarted);
        afterCommit.run(() -> worker.submit(run));
        return new IntegrationTaskView(taskId, IntegrationTaskStatus.PROCESSING, deadlineAt).toMap();
    }

    public Map<String, Object> resolve(UUID taskId, Map<String, Object> payload) {
        DataRecord request = DataRecord.ofNullable(payload);
        String reason = request.getOrDefault(REASON_PARAM, String.class, null);
        if (Utils.STR.isBlank(reason)) {
            throw CommonErrors.required(REASON_PARAM, REASON_PARAM + " is required");
        }
        IntegrationTaskStatus target = targetStatus(request.getOrDefault(STATUS_PARAM, String.class, null));
        Object data = request.getOrDefault(DATA_PARAM, Object.class, null);
        IntegrationTaskRow row = unconfirmedTask(taskId);
        AsyncIntegrationHandler<?, ?> handler = handlerOf(row);
        String username = RequestContextHolder.getRequestContext().getUsername();
        if (store.resolve(taskId, target, data, reason, username) == 0) {
            throw ObjectErrors.integrationTaskInvalidStatus(taskId, currentStatus(taskId));
        }
        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put(IntegrationTaskMeta.RESOLVED_BY.getFieldName(), username);
        resolution.put(IntegrationTaskMeta.RESOLVE_REASON.getFieldName(), reason);
        logChange(row, target, resolution);
        IntegrationTaskContext task = taskContexts.runOf(handler, row).task();
        if (target == IntegrationTaskStatus.SUCCEEDED) {
            runner.deliver(handler, task, ClassifiedOutcome.succeeded(IntegrationOutcome.RESOLVED, data));
            afterCommit.run(() -> runner.afterSucceededCommit(handler, task));
        } else {
            runner.deliver(handler, task, ClassifiedOutcome.failed(IntegrationOutcome.RESOLVED,
                    IntegrationError.of(IntegrationOutcome.RESOLVED, reason)));
        }
        return new IntegrationTaskView(taskId, target, row.deadlineAt()).toMap();
    }

    private IntegrationTaskRow unconfirmedTask(UUID taskId) {
        if (taskId == null) {
            throw CommonErrors.required(IntegrationTaskMeta.ID.getFieldName(), "id of the integration task is required");
        }
        IntegrationTaskRow row = store.findById(taskId).orElseThrow(() -> ObjectErrors.notFound(IntegrationTaskMeta.OBJECT_NAME));
        if (row.status() != IntegrationTaskStatus.UNCONFIRMED) {
            throw ObjectErrors.integrationTaskInvalidStatus(taskId, row.status().name());
        }
        return row;
    }

    private AsyncIntegrationHandler<?, ?> handlerOf(IntegrationTaskRow row) {
        return registry.find(row.connector(), row.action())
                .orElseThrow(() -> CommonErrors.unsupported(IntegrationTaskMeta.ACTION.getFieldName(),
                        "No AsyncIntegrationHandler for " + row.connector() + " / " + row.action()));
    }

    private IntegrationTaskStatus targetStatus(String value) {
        IntegrationTaskStatus status = Utils.STR.isBlank(value) ? null
                : RESOLVE_STATUSES.stream().filter(candidate -> candidate.name().equals(value)).findFirst().orElse(null);
        if (status == null) {
            throw CommonErrors.invalid(STATUS_PARAM, STATUS_PARAM + " must be SUCCEEDED or FAILED");
        }
        return status;
    }

    private String currentStatus(UUID taskId) {
        return store.findById(taskId).map(task -> task.status().name()).orElse(null);
    }

    /** Change log of the task when the service enables it for {@code integrationtask} (nothing otherwise). */
    private void logChange(IntegrationTaskRow row, IntegrationTaskStatus newStatus, Map<String, Object> changes) {
        String idField = IntegrationTaskMeta.ID.getFieldName();
        String statusField = IntegrationTaskMeta.STATUS.getFieldName();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put(idField, row.id());
        before.put(statusField, row.status().name());
        Map<String, Object> after = new LinkedHashMap<>(changes);
        after.put(idField, row.id());
        after.put(statusField, newStatus.name());
        manualChangeLog.update(IntegrationTaskMeta.OBJECT_NAME, before, after);
    }
}
