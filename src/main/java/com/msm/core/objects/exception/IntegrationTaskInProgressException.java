package com.msm.core.objects.exception;

import com.msm.core.exceptions.GenericBaseException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The record has an integration task PROCESSING (a call to a partner is waiting for its answer): the write is refused.
 * Services answer it with HTTP 409, code {@code integration.task.in_progress}.
 */
public class IntegrationTaskInProgressException extends GenericBaseException {
    public static final String OBJECT_PARAM = "object";
    public static final String RECORD_ID_PARAM = "recordId";
    public static final String TASK_ID_PARAM = "taskId";
    private static final String MESSAGE = "Bản ghi đang được gửi sang đối tác, vui lòng chờ trong giây lát";

    public IntegrationTaskInProgressException(String objectName, UUID recordId, UUID taskId, Throwable cause) {
        super(ObjectErrorCode.INTEGRATION_TASK_IN_PROGRESS, MESSAGE, params(objectName, recordId, taskId), cause);
    }

    private static Map<String, Object> params(String objectName, UUID recordId, UUID taskId) {
        Map<String, Object> params = new HashMap<>();
        params.put(OBJECT_PARAM, objectName);
        params.put(RECORD_ID_PARAM, recordId);
        if (taskId != null) {
            params.put(TASK_ID_PARAM, taskId);
        }
        return params;
    }
}
