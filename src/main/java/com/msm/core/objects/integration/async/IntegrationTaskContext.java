package com.msm.core.objects.integration.async;

import com.msm.core.commons.Utils;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;
import java.util.UUID;

/**
 * The call given to the result callbacks of {@link AsyncIntegrationHandler}. In sync mode (connector without
 * {@code async.enabled}) there is no task row: {@code taskId} is null. A result handled within
 * {@code async.inline-wait-ms} has its task ids and {@code async = false}.
 */
@Getter
@Builder(toBuilder = true)
@ToString(exclude = {"payload", "context"})
public class IntegrationTaskContext {
    private final UUID taskId;
    private final String connector;
    private final String objectName;
    private final UUID recordId;
    /** {@link AsyncIntegrationHandler#action()} of the handler. */
    private final String action;
    /**
     * The payload sent ({@link PreparedCall#payload()}). Null in a context rebuilt from the table (sweeper,
     * {@code resolve}): the payload is not stored; what was sent is in {@code integration_log}.
     */
    private final Object payload;
    private final Map<String, Object> context;
    /**
     * false: the callback runs in the action's transaction (sync, or a result within {@code async.inline-wait-ms}) and
     * may throw to fail the action, as a direct call did. true: it runs in the worker, the sweeper or the task action
     * {@code resolve}.
     */
    private final boolean async;

    public <X> X getPayloadAs(Class<X> type) {
        return Utils.O.convertToType(payload, type);
    }

    public <X> X getContextValue(String key, Class<X> type) {
        return context == null ? null : Utils.O.convertToType(context.get(key), type);
    }
}
