package com.msm.core.objects.exception;

import com.msm.core.exceptions.GenericBaseException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The request spent its {@code async.request-budget-ms} before the partner could be called (since 2.7.8): nothing was
 * sent and no task was created; the action's transaction rolls back and the user can try again. A business error,
 * code {@code integration.task.request_budget_exceeded}.
 */
public class IntegrationTaskRequestBudgetException extends GenericBaseException {
    public static final String OBJECT_PARAM = "object";
    public static final String RECORD_ID_PARAM = "recordId";
    public static final String ELAPSED_MS_PARAM = "elapsedMs";
    public static final String BUDGET_MS_PARAM = "budgetMs";
    private static final String MESSAGE = "Hệ thống phản hồi chậm, chưa gửi sang đối tác, vui lòng thử lại";

    public IntegrationTaskRequestBudgetException(String objectName, UUID recordId, long elapsedMs, long budgetMs) {
        super(ObjectErrorCode.INTEGRATION_TASK_REQUEST_BUDGET_EXCEEDED, MESSAGE,
                params(objectName, recordId, elapsedMs, budgetMs), null);
    }

    private static Map<String, Object> params(String objectName, UUID recordId, long elapsedMs, long budgetMs) {
        Map<String, Object> params = new HashMap<>();
        params.put(OBJECT_PARAM, objectName);
        params.put(RECORD_ID_PARAM, recordId);
        params.put(ELAPSED_MS_PARAM, elapsedMs);
        params.put(BUDGET_MS_PARAM, budgetMs);
        return params;
    }
}
