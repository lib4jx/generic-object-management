package com.msm.core.objects.integration.async;

import com.msm.core.action.context.ActionContext;
import com.msm.core.commons.Constants;
import com.msm.core.objects.entity.metadata.IntegrationTaskMeta;
import com.msm.core.objects.exception.ObjectErrors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Refuses a write on a record that has an integration task PROCESSING ({@code integration.task.in_progress}, 409): the
 * record must stay as the payload sent to the partner. Checked for every generic write ({@link
 * IntegrationTaskGuardedActionExecutor}); services call {@link #check(String, Object)} for writes on child objects
 * (e.g. the lines of an order). Does nothing, and reads nothing, while no connector has {@code async.enabled}.
 */
public class IntegrationTaskGuard {

    /** Reads, and creations (a new record has no task). */
    private static final Set<String> UNGUARDED_ACTIONS = Set.of(
            Constants.FilterAction.FILTER_OBJECT,
            Constants.FilterAction.FILTER_ALL_OBJECT,
            Constants.FilterAction.FILTER_ALL_OBJECT_BY_IDS,
            Constants.FilterAction.FILTER_OBJECT_BY_ID,
            Constants.FilterAction.LOOKUP_OBJECT,
            Constants.Action.CREATE,
            Constants.Action.BULK_CREATE);

    private final IntegrationTaskStore store;
    private final boolean enabled;

    public IntegrationTaskGuard(IntegrationTaskStore store, boolean enabled) {
        this.store = store;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** {@code integration.task.in_progress} when the record has a task PROCESSING. */
    public void check(String objectName, Object recordId) {
        check(objectName, recordId == null ? List.of() : List.of(recordId));
    }

    /** {@code integration.task.in_progress} when one of the records has a task PROCESSING. */
    public void check(String objectName, Collection<?> recordIds) {
        if (!enabled) {
            return;
        }
        List<UUID> ids = uuids(recordIds);
        if (ids.isEmpty()) {
            return;
        }
        store.findProcessing(objectName, ids).ifPresent(task -> {
            throw ObjectErrors.integrationTaskInProgress(objectName, task.recordId(), task.id());
        });
    }

    /**
     * A generic write: the {@code objectId} of the action, else the {@code id} of each map of a list payload (bulk
     * update / delete / action). Reads, creations, named queries and the task object itself are not checked.
     */
    public void check(ActionContext<?> request) {
        if (!enabled || !isGuardedWrite(request)) {
            return;
        }
        check(request.getResource(), recordIds(request));
    }

    private boolean isGuardedWrite(ActionContext<?> request) {
        String resource = request.getResource();
        // no action: the executor refuses the request itself
        return request.getAction() != null
                && !UNGUARDED_ACTIONS.contains(request.getAction())
                && !Constants.RESOURCE_QUERY_PREFIX.equals(resource)
                && !IntegrationTaskMeta.OBJECT_NAME.equalsIgnoreCase(resource);
    }

    private List<Object> recordIds(ActionContext<?> request) {
        if (request.getObjectId() != null) {
            return List.of(request.getObjectId());
        }
        List<Object> ids = new ArrayList<>();
        if (request.getPayload() instanceof Collection<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map && map.get(Constants.OBJECT_PK) != null) {
                    ids.add(map.get(Constants.OBJECT_PK));
                }
            }
        }
        return ids;
    }

    /** Ids that are not UUIDs cannot have a task (record_id is a UUID). */
    private List<UUID> uuids(Collection<?> values) {
        List<UUID> ids = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof UUID uuid) {
                ids.add(uuid);
            } else if (value instanceof String text && isUuid(text)) {
                ids.add(UUID.fromString(text));
            }
        }
        return ids;
    }

    private boolean isUuid(String text) {
        try {
            UUID.fromString(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
