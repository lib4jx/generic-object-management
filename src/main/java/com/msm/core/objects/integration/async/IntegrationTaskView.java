package com.msm.core.objects.integration.async;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What an action returns to the FE about its task ({@code integrationTask: {id, status, deadlineAt}}).
 *
 * @param id         task id, to read {@code integrationtask/{id}}
 * @param status     PROCESSING when just started
 * @param deadlineAt after it the task is UNCONFIRMED if still PROCESSING
 */
public record IntegrationTaskView(UUID id, IntegrationTaskStatus status, Instant deadlineAt) {

    public static final String RESPONSE_FIELD = "integrationTask";

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("status", status.name());
        map.put("deadlineAt", deadlineAt);
        return map;
    }
}
