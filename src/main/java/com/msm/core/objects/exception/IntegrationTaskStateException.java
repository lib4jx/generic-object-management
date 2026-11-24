package com.msm.core.objects.exception;

import com.msm.core.exceptions.GenericBaseException;
import com.msm.core.exceptions.common.ErrorCode;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Action {@code resolve} not allowed on the task in its current state (HTTP 400). */
public class IntegrationTaskStateException extends GenericBaseException {
    public static final String TASK_ID_PARAM = "taskId";
    public static final String STATUS_PARAM = "status";

    public IntegrationTaskStateException(ErrorCode code, UUID taskId, String status, String message) {
        super(code, message, params(taskId, status));
    }

    private static Map<String, Object> params(UUID taskId, String status) {
        Map<String, Object> params = new HashMap<>();
        params.put(TASK_ID_PARAM, taskId);
        if (status != null) {
            params.put(STATUS_PARAM, status);
        }
        return params;
    }
}
