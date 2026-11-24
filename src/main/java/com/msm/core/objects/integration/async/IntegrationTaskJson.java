package com.msm.core.objects.integration.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.msm.core.commons.Utils;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON of the column {@code context}, with the commons mapper. */
class IntegrationTaskJson {

    String write(Object value) {
        try {
            return Utils.O.toJsonString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write integration_task json: " + e.getOriginalMessage(), e);
        }
    }

    Map<String, Object> readMap(String json) {
        if (json == null) {
            return new LinkedHashMap<>();
        }
        return Utils.O.convertToType(json, new TypeReference<Map<String, Object>>() {});
    }
}
