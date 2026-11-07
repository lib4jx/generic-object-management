package com.msm.core.objects.schema.dto;

import java.util.List;

/**
 * Read-only description of a generic object, built from the object metadata the service registered from its JPA
 * entity: what another service may read about it, without its storage, query or security details.
 *
 * @param object     object name (lower case)
 * @param idField    the primary key attribute
 * @param hash       SHA-256 of the schema content: changes when an attribute, a type, an enum value or a reference
 *                   changes
 * @param attributes attributes sorted by name; audit, soft-delete, workflow and {@code customValues} fields are left
 *                   out
 */
public record ObjectSchema(String object, String idField, String hash, List<AttributeSchema> attributes) {
}
