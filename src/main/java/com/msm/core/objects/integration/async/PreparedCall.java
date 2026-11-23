package com.msm.core.objects.integration.async;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Result of {@link AsyncIntegrationHandler#prepare}: the payload sent to the partner (column {@code request}) and the
 * data of the action the result step needs (column {@code context}).
 *
 * @param <P>      type of the payload
 * @param payload  sent to the partner
 * @param context  data the result step needs; never null
 * @param recordId record the task locks; null: the {@code objectId} of the action (set it for a bulk action)
 */
public record PreparedCall<P>(P payload, Map<String, Object> context, UUID recordId) {

    public PreparedCall {
        context = context == null ? new LinkedHashMap<>() : context;
    }

    public static <P> PreparedCall<P> of(P payload) {
        return new PreparedCall<>(payload, null, null);
    }

    public static <P> PreparedCall<P> of(P payload, Map<String, Object> context) {
        return new PreparedCall<>(payload, context, null);
    }

    public PreparedCall<P> forRecord(UUID id) {
        return new PreparedCall<>(payload, context, id);
    }
}
