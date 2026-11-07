package com.msm.core.objects.schema;

import com.msm.core.dynamicquery.ObjectMetadataFactory;
import com.msm.core.exceptions.ObjectNotFoundException;
import com.msm.core.metadata.Attribute;
import com.msm.core.metadata.ObjectMetadata;
import com.msm.core.objects.entity.imports.ImportJob;
import com.msm.core.objects.schema.dto.ObjectSchema;
import com.msm.core.objects.schema.dto.ObjectSchemaSummary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObjectSchemaServiceTest {

    /** Unique names: ObjectMetadataFactory is a static registry shared by every test. */
    private static final String SITE = "schematestsite";
    private static final String NO_METADATA = "schematestnometadata";

    private ObjectSchemaService service;

    @BeforeEach
    void setUp() {
        ObjectMetadataFactory.registerObjectMetadata(metadata(SITE, "code"));
        EntityManager entityManager = mock(EntityManager.class);
        Metamodel metamodel = mock(Metamodel.class);
        when(entityManager.getMetamodel()).thenReturn(metamodel);
        Set<EntityType<?>> entities = Set.of(
                entity("SchemaTestSite", String.class),
                entity("SchemaTestNoMetadata", Integer.class),
                entity("ImportJob", ImportJob.class));
        when(metamodel.getEntities()).thenReturn(entities);
        service = new ObjectSchemaService(entityManager, new ObjectSchemaMapper());
    }

    @Test
    void anObjectIsFoundWhateverTheCaseOfItsName() {
        assertThat(service.getSchema("SchemaTestSite").object()).isEqualTo(SITE);
    }

    @Test
    void theSchemaIsBuiltOncePerMetadata() {
        ObjectSchema first = service.getSchema(SITE);

        assertThat(service.getSchema(SITE)).isSameAs(first);

        ObjectMetadataFactory.registerObjectMetadata(metadata(SITE, "name"));
        ObjectSchema again = service.getSchema(SITE);
        assertThat(again).isNotSameAs(first);
        assertThat(again.hash()).isNotEqualTo(first.hash());
    }

    @Test
    void anUnknownObjectIsNotFound() {
        assertThatThrownBy(() -> service.getSchema(NO_METADATA)).isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    void theListHoldsTheServiceObjectsWithMetadata() {
        // the library's own entities (import / export jobs) and entities without metadata are left out
        assertThat(service.getSchemas()).extracting(ObjectSchemaSummary::object).containsExactly(SITE);
        assertThat(service.getSchemas().getFirst().hash()).isEqualTo(service.getSchema(SITE).hash());
    }

    private ObjectMetadata metadata(String name, String field) {
        Attribute id = new Attribute();
        id.setFieldName("id");
        id.setFieldType("UUID");
        Attribute other = new Attribute();
        other.setFieldName(field);
        other.setFieldType("String");
        return ObjectMetadata.builder().name(name).attributes(new ArrayList<>(List.of(id, other))).build();
    }

    @SuppressWarnings("unchecked")
    private <T> EntityType<T> entity(String name, Class<T> javaType) {
        EntityType<T> entity = mock(EntityType.class);
        when(entity.getName()).thenReturn(name);
        when(entity.getJavaType()).thenReturn(javaType);
        return entity;
    }
}
