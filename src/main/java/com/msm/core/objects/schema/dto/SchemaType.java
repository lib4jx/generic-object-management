package com.msm.core.objects.schema.dto;

/** Logical type of an attribute in an {@link ObjectSchema}, independent of the Java and column types. */
public enum SchemaType {
    STRING,
    NUMBER,
    BOOLEAN,
    DATE,
    DATETIME,
    UUID,
    STRING_LIST,
    UUID_LIST,
    JSON,
    OTHER
}
