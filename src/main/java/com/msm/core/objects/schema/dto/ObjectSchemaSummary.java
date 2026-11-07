package com.msm.core.objects.schema.dto;

/** An object of the service and the hash of its schema, to find the schemas that changed. */
public record ObjectSchemaSummary(String object, String hash) {
}
