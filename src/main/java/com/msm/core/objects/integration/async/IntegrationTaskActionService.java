package com.msm.core.objects.integration.async;

import com.msm.core.commons.Utils;
import com.msm.core.exceptions.CommonErrors;
import com.msm.core.metadata.typesafe.DataRecord;
import com.msm.core.objects.audit.changelog.ManualChangeLog;
import com.msm.core.objects.entity.metadata.IntegrationTaskMeta;
import com.msm.core.objects.exception.ObjectErrors;
import com.msm.core.security.RequestContextHolder;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Action {@code resolve} on an UNCONFIRMED task (design §6, §18), in the transaction of the action route: closes the
 * task by hand, {@code status} SUCCEEDED (with {@code data}, the answer the handler expects, given to
 * {@code onSucceeded} as the response and not stored) or FAILED, {@code reason} required; the handler's callback runs
 * as for a call result, with the task's {@code context} (no payload). There is no resend: after UNCONFIRMED / FAILED
 * the user runs the original action again (new task).
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
    private final IntegrationCallRunner runner;
    private final ManualChangeLog manualChangeLog;
    private final AfterCommit afterCommit = new AfterCommit();
    private final TaskContextFactory taskContexts = new TaskContextFactory();

    public Map<String, Object> resolve(UUID taskId, Map<String, Object> payload) {
        DataRecord request = DataRecord.ofNullable(payload);
        String reason = request.getOrDefault(REASON_PARAM, String.class, null);
        if (Utils.STR.isBlank(reason)) {
            throw CommonErrors.required(REASON_PARAM, "Lý do là bắt buộc");
        }
        IntegrationTaskStatus target = targetStatus(request.getOrDefault(STATUS_PARAM, String.class, null));
        Object data = request.getOrDefault(DATA_PARAM, Object.class, null);
        IntegrationTaskRow row = unconfirmedTask(taskId);
        AsyncIntegrationHandler<?, ?> handler = handlerOf(row);
        String username = RequestContextHolder.getRequestContext().getUsername();
        if (store.resolve(taskId, target, reason, username) == 0) {
            throw ObjectErrors.integrationTaskInvalidStatus(taskId, currentStatus(taskId));
        }
        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put(IntegrationTaskMeta.RESOLVED_BY.getFieldName(), username);
        resolution.put(IntegrationTaskMeta.RESOLVE_REASON.getFieldName(), reason);
        logChange(row, target, resolution);
        IntegrationTaskContext task = taskContexts.contextOf(row);
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
            throw CommonErrors.invalid(STATUS_PARAM, "Trạng thái chốt phải là SUCCEEDED hoặc FAILED");
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
