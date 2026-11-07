package com.msm.core.objects.schema;

import com.msm.core.metadata.Attribute;
import com.msm.core.metadata.AttributeRef;
import com.msm.core.metadata.ObjectMetadata;
import com.msm.core.objects.schema.dto.AttributeSchema;
import com.msm.core.objects.schema.dto.ObjectSchema;
import com.msm.core.objects.schema.dto.RefSchema;
import com.msm.core.objects.schema.dto.SchemaType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectSchemaMapperTest {

    enum SiteStatus { ACTIVE, INACTIVE }

    private final ObjectSchemaMapper mapper = new ObjectSchemaMapper();

    @Test
    void storageSecurityAndSystemFieldsAreLeftOut() {
        ObjectSchema schema = mapper.toSchema(accountSite());

        assertThat(schema.object()).isEqualTo("accountsite");
        assertThat(schema.idField()).isEqualTo("id");
        assertThat(schema.attributes()).extracting(AttributeSchema::name)
                .containsExactly("code", "extra", "id", "plain", "provinceId", "status", "tags", "totalAmount", "validFrom", "wardIds")
                .doesNotContain("createdAt", "updatedBy", "isDeleted", "version", "customValues", "orgId");
    }

    @Test
    void typesFollowTheDeclaredJavaType() {
        Map<String, AttributeSchema> attributes = byName(mapper.toSchema(accountSite()));

        assertThat(attributes.get("id").type()).isEqualTo(SchemaType.UUID);
        assertThat(attributes.get("code").type()).isEqualTo(SchemaType.STRING);
        assertThat(attributes.get("code").required()).isTrue();
        assertThat(attributes.get("totalAmount").type()).isEqualTo(SchemaType.NUMBER);
        assertThat(attributes.get("validFrom").type()).isEqualTo(SchemaType.DATE);
        assertThat(attributes.get("tags").type()).isEqualTo(SchemaType.STRING_LIST);
        assertThat(attributes.get("tags").multiSelect()).isTrue();
        assertThat(attributes.get("wardIds").type()).isEqualTo(SchemaType.UUID_LIST);
        assertThat(attributes.get("extra").type()).isEqualTo(SchemaType.JSON);
        assertThat(attributes.get("totalAmount").javaType()).isEqualTo("BigDecimal");
    }

    @Test
    void anEnumGivesItsExactConstantNames() {
        AttributeSchema status = byName(mapper.toSchema(accountSite())).get("status");

        assertThat(status.type()).isEqualTo(SchemaType.STRING);
        assertThat(status.enumValues()).containsExactly("ACTIVE", "INACTIVE");
    }

    @Test
    void aReferenceNamesItsObjectAndSnapshot() {
        Map<String, AttributeSchema> attributes = byName(mapper.toSchema(accountSite()));

        assertThat(attributes.get("provinceId").ref())
                .isEqualTo(new RefSchema("geography", "provinceIdReference", List.of("code", "id", "name")));
        // @AttributeDefinition without a reference yields an empty AttributeRef: not a reference
        assertThat(attributes.get("plain").ref()).isNull();
    }

    @Test
    void theHashIsStableAndFollowsTheContent() {
        String hash = mapper.toSchema(accountSite()).hash();

        assertThat(mapper.toSchema(accountSite()).hash()).isEqualTo(hash).startsWith("sha256:");

        ObjectMetadata changed = accountSite();
        changed.getAttributes().stream().filter(a -> a.getFieldName().equals("code")).findFirst().orElseThrow().setIsRequired(false);
        assertThat(mapper.toSchema(changed).hash()).isNotEqualTo(hash);

        ObjectMetadata auditOnly = accountSite();
        auditOnly.getAttributes().add(attribute("createdById", "UUID"));
        assertThat(mapper.toSchema(auditOnly).hash()).isEqualTo(hash);
    }

    private Map<String, AttributeSchema> byName(ObjectSchema schema) {
        return schema.attributes().stream().collect(Collectors.toMap(AttributeSchema::name, Function.identity()));
    }

    private ObjectMetadata accountSite() {
        Attribute id = attribute("id", "UUID");
        id.setIsSystem(true);
        Attribute code = attribute("code", "String");
        code.setIsRequired(true);
        Attribute province = attribute("provinceId", "UUID");
        province.setAttributeRef(new AttributeRef("provinceIdReference", "Geography", "Reference", Set.of("id", "code", "name")));
        Attribute plain = attribute("plain", "String");
        plain.setAttributeRef(AttributeRef.of("", ""));
        Attribute status = attribute("status", "String");
        status.setEnumType(SiteStatus.class);
        Attribute extra = attribute("extra", "Map<String, Object>");
        extra.setIsJson(true);
        Attribute customValues = attribute("customValues", "Map<String, Object>");
        customValues.setIsJson(true);
        List<Attribute> attributes = new ArrayList<>(List.of(id, code, province, plain, status, extra, customValues,
                attribute("tags", "List<String>"),
                attribute("wardIds", "List<UUID>"),
                attribute("totalAmount", "BigDecimal"),
                attribute("validFrom", "LocalDate"),
                attribute("createdAt", "Instant"),
                attribute("updatedBy", "String"),
                attribute("isDeleted", "Boolean"),
                attribute("orgId", "UUID"),
                attribute("version", "Long")));
        return ObjectMetadata.builder().name("accountsite").tableName("account_site").attributes(attributes).build();
    }

    private Attribute attribute(String name, String type) {
        Attribute attribute = new Attribute();
        attribute.setFieldName(name);
        attribute.setFieldType(type);
        attribute.setColumnName(name);
        return attribute;
    }
}
