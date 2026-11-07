package com.msm.core.objects.schema;

import com.msm.core.commons.Constants;
import com.msm.core.metadata.Attribute;
import com.msm.core.metadata.AttributeRef;
import com.msm.core.metadata.ObjectMetadata;
import com.msm.core.objects.schema.dto.AttributeSchema;
import com.msm.core.objects.schema.dto.ObjectSchema;
import com.msm.core.objects.schema.dto.RefSchema;
import com.msm.core.objects.schema.dto.SchemaType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns the {@link ObjectMetadata} a service registered from its JPA entity into the read-only {@link ObjectSchema}
 * other services read: no jOOQ fields, table or column names, security scopes; no audit, soft-delete, workflow,
 * version or {@code customValues} fields (the reference snapshots in {@code customValues} are described by
 * {@link RefSchema} instead).
 */
public class ObjectSchemaMapper {

    /** Fields of {@code AuditingEntity}, {@code BusinessProcessEntity}, {@code SoftDeleteEntity}, plus version and custom values. */
    static final Set<String> HIDDEN_FIELDS = Set.of(
            "createdBy", "createdById", "createdAt", "updatedBy", "updatedById", "updatedAt",
            "teamId", "orgId", "workflowInstanceId", "recordTypeId", "stageIds", "approvalStatus", "lockedEdit",
            "deletedBy", "deletedById", "deletedAt", "isDeleted",
            "version", "customValues");

    private static final Map<String, SchemaType> SCALAR_TYPES = Map.ofEntries(
            Map.entry("String", SchemaType.STRING),
            Map.entry("UUID", SchemaType.UUID),
            Map.entry("BigDecimal", SchemaType.NUMBER),
            Map.entry("BigInteger", SchemaType.NUMBER),
            Map.entry("Integer", SchemaType.NUMBER),
            Map.entry("int", SchemaType.NUMBER),
            Map.entry("Long", SchemaType.NUMBER),
            Map.entry("long", SchemaType.NUMBER),
            Map.entry("Short", SchemaType.NUMBER),
            Map.entry("Double", SchemaType.NUMBER),
            Map.entry("double", SchemaType.NUMBER),
            Map.entry("Float", SchemaType.NUMBER),
            Map.entry("Boolean", SchemaType.BOOLEAN),
            Map.entry("boolean", SchemaType.BOOLEAN),
            Map.entry("LocalDate", SchemaType.DATE),
            Map.entry("Instant", SchemaType.DATETIME),
            Map.entry("LocalDateTime", SchemaType.DATETIME),
            Map.entry("OffsetDateTime", SchemaType.DATETIME),
            Map.entry("ZonedDateTime", SchemaType.DATETIME),
            Map.entry("Date", SchemaType.DATETIME),
            Map.entry("Timestamp", SchemaType.DATETIME));

    public ObjectSchema toSchema(ObjectMetadata metadata) {
        List<AttributeSchema> attributes = metadata.getAttributes() == null ? List.of() : metadata.getAttributes().stream()
                .filter(Objects::nonNull)
                .filter(attribute -> attribute.getFieldName() != null && !HIDDEN_FIELDS.contains(attribute.getFieldName()))
                .map(this::toAttribute)
                .sorted(Comparator.comparing(AttributeSchema::name))
                .toList();
        String object = metadata.getName();
        String idField = metadata.getIdAttribute() != null ? Constants.OBJECT_PK : null;
        return new ObjectSchema(object, idField, hash(object, idField, attributes), attributes);
    }

    AttributeSchema toAttribute(Attribute attribute) {
        List<String> enumValues = attribute.getEnumType() == null ? null
                : Arrays.stream(attribute.getEnumType().getEnumConstants()).map(Enum::name).toList();
        return new AttributeSchema(
                attribute.getFieldName(),
                enumValues != null ? SchemaType.STRING : typeOf(attribute),
                attribute.getFieldType(),
                Boolean.TRUE.equals(attribute.getIsRequired()),
                Boolean.TRUE.equals(attribute.getMultiSelect()) || isList(attribute.getFieldType()),
                enumValues,
                refOf(attribute.getAttributeRef()));
    }

    SchemaType typeOf(Attribute attribute) {
        String fieldType = attribute.getFieldType();
        if (fieldType == null) {
            return SchemaType.OTHER;
        }
        if (isList(fieldType)) {
            String element = fieldType.substring(fieldType.indexOf('<') + 1, fieldType.lastIndexOf('>')).trim();
            return switch (element) {
                case "String" -> SchemaType.STRING_LIST;
                case "UUID" -> SchemaType.UUID_LIST;
                default -> SchemaType.OTHER;
            };
        }
        if (fieldType.endsWith("[]")) {
            return "String[]".equals(fieldType) ? SchemaType.STRING_LIST : SchemaType.OTHER;
        }
        SchemaType scalar = SCALAR_TYPES.get(fieldType);
        if (scalar != null) {
            return scalar;
        }
        return Boolean.TRUE.equals(attribute.getIsJson()) || fieldType.startsWith("Map<") ? SchemaType.JSON : SchemaType.OTHER;
    }

    private boolean isList(String fieldType) {
        return fieldType != null && (fieldType.startsWith("List<") || fieldType.startsWith("Set<"));
    }

    /** A reference only when it names its object; {@code @AttributeDefinition} without a ref yields an empty one. */
    private RefSchema refOf(AttributeRef ref) {
        if (ref == null || ref.getObjectRef() == null || ref.getObjectRef().isBlank()) {
            return null;
        }
        List<String> snapshotFields = ref.getAttributeRefs() == null || ref.getAttributeRefs().isEmpty() ? null
                : ref.getAttributeRefs().stream().sorted().toList();
        String snapshotField = ref.getFieldName() == null || ref.getFieldName().isBlank() ? null : ref.getFieldName();
        return new RefSchema(ref.getObjectRef().toLowerCase(), snapshotField, snapshotFields);
    }

    /** SHA-256 over the schema content in a fixed order, so the same metadata gives the same hash on every instance. */
    String hash(String object, String idField, List<AttributeSchema> attributes) {
        String content = Stream.concat(Stream.of(object + "|" + idField), attributes.stream().map(this::line))
                .collect(Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String line(AttributeSchema attribute) {
        RefSchema ref = attribute.ref();
        return String.join("|",
                attribute.name(),
                String.valueOf(attribute.type()),
                String.valueOf(attribute.javaType()),
                String.valueOf(attribute.required()),
                String.valueOf(attribute.multiSelect()),
                attribute.enumValues() == null ? "" : String.join(",", attribute.enumValues()),
                ref == null ? "" : ref.object() + ">" + ref.snapshotField() + ">"
                        + (ref.snapshotFields() == null ? "" : String.join(",", ref.snapshotFields())));
    }
}
