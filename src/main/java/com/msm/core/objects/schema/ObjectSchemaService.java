package com.msm.core.objects.schema;

import com.msm.core.dynamicquery.ObjectMetadataFactory;
import com.msm.core.metadata.ObjectMetadata;
import com.msm.core.objects.exception.ObjectErrors;
import com.msm.core.objects.schema.dto.ObjectSchema;
import com.msm.core.objects.schema.dto.ObjectSchemaSummary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only schemas of the generic objects of this service, built from the metadata registered from the JPA entities
 * at start-up ({@code ObjectMetadataFactory}). Nothing is stored: the entity stays the source of truth. A schema is
 * built once per metadata instance and rebuilt when the object's metadata is registered again.
 */
public class ObjectSchemaService {

    /** The library's own entities (import / export jobs…) are not objects of the service. */
    private static final String LIBRARY_PACKAGE = "com.msm.core.objects.";

    private final EntityManager entityManager;
    private final ObjectSchemaMapper mapper;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ObjectSchemaService(EntityManager entityManager, ObjectSchemaMapper mapper) {
        this.entityManager = entityManager;
        this.mapper = mapper;
    }

    /** The schema of one object of the service; {@code ObjectErrors.notFound} when the service has no such object. */
    public ObjectSchema getSchema(String objectName) {
        String key = objectName == null ? null : objectName.toLowerCase();
        return find(key).orElseThrow(() -> ObjectErrors.notFound(objectName));
    }

    /** Every object of the service with the hash of its schema, by name. */
    public List<ObjectSchemaSummary> getSchemas() {
        return entityManager.getMetamodel().getEntities().stream()
                .filter(entity -> !entity.getJavaType().getName().startsWith(LIBRARY_PACKAGE))
                .map(EntityType::getName)
                .map(String::toLowerCase)
                .distinct()
                .map(this::find)
                .flatMap(Optional::stream)
                .map(schema -> new ObjectSchemaSummary(schema.object(), schema.hash()))
                .sorted(Comparator.comparing(ObjectSchemaSummary::object))
                .toList();
    }

    private Optional<ObjectSchema> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return ObjectMetadataFactory.getObjectMetadata(key).map(metadata -> {
            Cached cached = cache.get(key);
            if (cached != null && cached.metadata() == metadata) {
                return cached.schema();
            }
            ObjectSchema schema = mapper.toSchema(metadata);
            cache.put(key, new Cached(metadata, schema));
            return schema;
        });
    }

    private record Cached(ObjectMetadata metadata, ObjectSchema schema) {
    }
}
