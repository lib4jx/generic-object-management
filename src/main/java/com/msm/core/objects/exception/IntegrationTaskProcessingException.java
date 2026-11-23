package com.msm.core.objects.exception;

import com.msm.core.exceptions.GenericBaseException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The action waited {@code async.inline-wait-ms} for the partner without a result: the call goes on in the background
 * and its task records the result; the action's transaction rolls back. Services answer it with HTTP 409, code
 * {@code integration.task.processing}.
 */
public class IntegrationTaskProcessingException extends GenericBaseException {
    public static final String OBJECT_PARAM = "object";
    public static final String RECORD_ID_PARAM = "recordId";
    public static final String TASK_ID_PARAM = "taskId";
    private static final String MESSAGE = "Tiến trình gửi ERP vẫn đang chạy, vui lòng tải lại sau ít phút";

    public IntegrationTaskProcessingException(String objectName, UUID recordId, UUID taskId, Throwable cause) {
        super(ObjectErrorCode.INTEGRATION_TASK_PROCESSING, MESSAGE, params(objectName, recordId, taskId), cause);
    }

    private static Map<String, Object> params(String objectName, UUID recordId, UUID taskId) {
        Map<String, Object> params = new HashMap<>();
        params.put(OBJECT_PARAM, objectName);
        params.put(RECORD_ID_PARAM, recordId);
        params.put(TASK_ID_PARAM, taskId);
        return params;
    }
}
