package com.msm.core.objects.dataexchange.exports.excel.handler;

import com.msm.core.action.context.ActionContext;
import com.msm.core.dynamicquery.ObjectMetadataFactory;
import com.msm.core.metadata.Attribute;
import com.msm.core.metadata.AttributeRef;
import com.msm.core.metadata.ObjectMetadata;
import com.msm.core.objects.dataexchange.ColumnToAttributeMappingContext;
import com.msm.core.objects.dataexchange.exports.model.ColumnHeaderPath;
import com.msm.core.objects.utils.JsonPathUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Template headers of an export: attributes, references and paths into a JSON attribute. */
class ExportExcelHandlerHeaderTest {
    private static final String OBJECT = "testexportline";

    private final ExportExcelHandler handler = new ExportExcelHandler();

    @BeforeAll
    static void registerMetadata() {
        Attribute customerId = attribute("customerId", "UUID");
        customerId.setAttributeRef(new AttributeRef("customerIdReference", "testcustomer"));
        ObjectMetadataFactory.registerObjectMetadata(ObjectMetadata.builder()
                .name(OBJECT)
                .tableName("test_export_line")
                .attributes(List.of(attribute("id", "UUID"), customerId, attribute("trader2Info", "Object")))
                .build());
    }

    @Test
    void keepsAttributeReferenceAndJsonPathColumns() throws IOException {
        Map<Integer, ColumnHeaderPath> columns = detect(
                "id", "customer_id", "customerIdReference.accountName", "trader2Info.code", "trader2Info.customer.code");

        assertThat(columns).containsOnlyKeys(0, 1, 2, 3, 4);
        assertThat(columns.get(0).isReference()).isFalse();
        assertThat(columns.get(1).isReference()).isFalse();
        assertThat(columns.get(2).isReference()).isTrue();
        assertThat(columns.get(4).isReference()).isTrue();
        assertThat(columns.get(4).referenceFieldName()).isEqualTo("trader2Info");
        assertThat(columns.get(4).originalPath()).isEqualTo("trader2Info.customer.code");
    }

    @Test
    void dropsPathsWhoseRootIsNotOnTheObject() throws IOException {
        Map<Integer, ColumnHeaderPath> columns = detect("id", "contents.maNumber", "unknown.customer.code", "Mã khách hàng");

        assertThat(columns).containsOnlyKeys(0);
    }

    @Test
    void nestedPathOfADetectedColumnReadsTheValue() throws IOException {
        ColumnHeaderPath path = detect("trader2Info.customer.code").get(0);
        Map<String, Object> row = Map.of("trader2Info", Map.of("customer", Map.of("code", "C0001")));

        assertThat(JsonPathUtil.<Object>extractValue(row, path.originalPath())).isEqualTo("C0001");
    }

    private Map<Integer, ColumnHeaderPath> detect(String... headers) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Row row = workbook.createSheet().createRow(0);
            for (int i = 0; i < headers.length; i++) {
                row.createCell(i).setCellValue(headers[i]);
            }
            return handler.attributeColumnHeaderMapping(ActionContext
                    .<ColumnToAttributeMappingContext<Row>>builder()
                    .resource(OBJECT)
                    .payload(ColumnToAttributeMappingContext.of(UUID.randomUUID(), 0, OBJECT, row))
                    .build());
        }
    }

    private static Attribute attribute(String fieldName, String fieldType) {
        return Attribute.builder().fieldName(fieldName).columnName(fieldName).fieldType(fieldType).build();
    }
}
