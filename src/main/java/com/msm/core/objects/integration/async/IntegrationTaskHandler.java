package com.msm.core.objects.integration.async;

import com.msm.core.action.annotations.action.Handler;
import com.msm.core.action.context.ActionContext;
import com.msm.core.commons.Constants;
import com.msm.core.exceptions.CommonErrors;
import com.msm.core.exceptions.UnsupportedException;
import com.msm.core.objects.entity.metadata.IntegrationTaskMeta;
import com.msm.core.security.annotations.SecuredPermissionAction;
import com.msm.core.security.enums.PermissionAction;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * Routes of the object {@code integrationtask}: read like any generic object; {@code PUT /integrationtask/{id}/retry}
 * and {@code /resolve} need the permission UPDATE on {@code integrationtask}; create / update / delete are refused
 * (only the library writes tasks).
 */
@RequiredArgsConstructor
public class IntegrationTaskHandler {

    private static final String READ_ONLY_MESSAGE = "integrationtask is written by the library only";

    private final IntegrationTaskActionService actionService;

    @Handler(resource = IntegrationTaskMeta.OBJECT_NAME, action = IntegrationTaskMeta.ACTION_RETRY)
    @SecuredPermissionAction(anyOf = PermissionAction.UPDATE)
    public Map<String, Object> retry(ActionContext<Map<String, Object>> actionContext) {
        return actionService.retry(taskId(actionContext));
    }

    @Handler(resource = IntegrationTaskMeta.OBJECT_NAME, action = IntegrationTaskMeta.ACTION_RESOLVE)
    @SecuredPermissionAction(anyOf = PermissionAction.UPDATE)
    public Map<String, Object> resolve(ActionContext<Map<String, Object>> actionContext) {
        return actionService.resolve(taskId(actionContext), actionContext.getPayload());
    }

    @Handler(resource = IntegrationTaskMeta.OBJECT_NAME, action = Constants.Action.CREATE)
    public Object create(ActionContext<?> actionContext) {
        throw readOnly(actionContext);
    }

    @Handler(resource = IntegrationTaskMeta.OBJECT_NAME, action = Constants.Action.BULK_CREATE)
    public Object bulkCreate(ActionContext<?> actionContext) {
        throw readOnly(actionContext);
    }

    @Handler(resource = IntegrationTaskMeta.OBJECT_NAME, action = Constants.Action.UPDATE)
    public Object update(ActionContext<?> actionContext) {
        throw readOnly(actionContext);
    }

    @Handler(resource = IntegrationTaskMeta.OBJECT_NAME, action = Constants.Action.BULK_UPDATE)
    public Object bulkUpdate(ActionContext<?> actionContext) {
        throw readOnly(actionContext);
    }

    @Handler(resource = IntegrationTaskMeta.OBJECT_NAME, action = Constants.Action.DELETE)
    public Object delete(ActionContext<?> actionContext) {
        throw readOnly(actionContext);
    }

    @Handler(resource = IntegrationTaskMeta.OBJECT_NAME, action = Constants.Action.BULK_DELETE)
    public Object bulkDelete(ActionContext<?> actionContext) {
        throw readOnly(actionContext);
    }

    private UUID taskId(ActionContext<?> actionContext) {
        return actionContext.getObjectId() == null ? null : actionContext.getObjectIdAs(UUID.class);
    }

    private UnsupportedException readOnly(ActionContext<?> actionContext) {
        return CommonErrors.unsupported(actionContext.getAction(), READ_ONLY_MESSAGE);
    }
}
