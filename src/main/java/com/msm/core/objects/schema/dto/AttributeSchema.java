package com.msm.core.objects.schema.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One attribute of an {@link ObjectSchema}.
 *
 * @param name        field name, as in the generic object API
 * @param type        logical type
 * @param javaType    the Java type the metadata declares (for example {@code BigDecimal}, {@code List<String>})
 * @param required    whether a value is required
 * @param multiSelect whether the attribute holds several values
 * @param enumValues  the allowed values (exact constant names) when the attribute has an enum type
 * @param ref         the referenced object, when the attribute is a reference
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttributeSchema(String name,
                              SchemaType type,
                              String javaType,
                              Boolean required,
                              Boolean multiSelect,
                              List<String> enumValues,
                              RefSchema ref) {
}
