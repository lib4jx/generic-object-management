package com.msm.core.objects.integration.async;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationTaskLogReaderTest {

    private static final DSLContext MOCK_DSL = DSL.using(SQLDialect.POSTGRES);
    private static final Field<UUID> ID = DSL.field(DSL.name("id"), SQLDataType.UUID);
    private static final Field<OffsetDateTime> CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> COMPLETED_AT = DSL.field(DSL.name("completed_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    private static final Field<Integer> STATUS_CODE = DSL.field(DSL.name("status_code"), SQLDataType.INTEGER);
    private static final Field<String> STATUS = DSL.field(DSL.name("status"), SQLDataType.VARCHAR);
    private static final Field<String> REQUEST_BODY = DSL.field(DSL.name("request_body"), SQLDataType.CLOB);
    private static final Field<String> RESPONSE_BODY = DSL.field(DSL.name("response_body"), SQLDataType.CLOB);
    private static final Field<String> ERROR_CODE = DSL.field(DSL.name("error_code"), SQLDataType.VARCHAR);
    private static final Field<String> ERROR_MESSAGE = DSL.field(DSL.name("error_message"), SQLDataType.CLOB);
    private static final Field<Long> TIMEOUT_MS = DSL.field(DSL.name("timeout_ms"), SQLDataType.BIGINT);

    private final List<MockExecuteContext> executed = new ArrayList<>();
    private Result<Record> rows = MOCK_DSL.newResult(List.of(ID, CREATED_AT, COMPLETED_AT, STATUS_CODE, STATUS, REQUEST_BODY,
            RESPONSE_BODY, ERROR_CODE, ERROR_MESSAGE, TIMEOUT_MS));

    private IntegrationTaskLogReader reader() {
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            executed.add(ctx);
            return new MockResult[]{new MockResult(rows.size(), rows)};
        }), SQLDialect.POSTGRES);
        return new IntegrationTaskLogReader(dsl);
    }

    private IntegrationTaskRow task(Instant startedAt, Instant completedAt, IntegrationTaskStatus status) {
        return new IntegrationTaskRow(UUID.randomUUID(), "erp", "v2order", UUID.randomUUID(), "erp", status, null,
                UUID.randomUUID(), 1, "{}", null, null, null, null, startedAt, startedAt.plusSeconds(360), completedAt,
                "user", startedAt, completedAt, 1);
    }

    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** jOOQ binds a timestamp with time zone of Postgres as its text, e.g. {@code 2026-11-24 03:00:00+00:00}. */
    private Instant instantOf(Object binding) {
        return binding instanceof OffsetDateTime time ? time.toInstant()
                : OffsetDateTime.parse(binding.toString().replace(' ', 'T')).toInstant();
    }

    @Test
    void finishedTaskIsReadByCorrelationIdWithinItsWindowOldestFirst() {
        // Arrange
        Instant startedAt = Instant.parse("2026-11-24T03:00:00Z");
        Instant completedAt = Instant.parse("2026-11-24T03:01:10Z");
        IntegrationTaskRow task = task(startedAt, completedAt, IntegrationTaskStatus.SUCCEEDED);

        // Act
        reader().findLogs(task);

        // Assert: the created_at bounds let Postgres prune the partitions of integration_log
        assertThat(executed).hasSize(1);
        String sql = executed.get(0).sql();
        assertThat(sql).contains("from \"integration_log\"")
                .contains("\"correlation_id\" = ?")
                .contains("\"created_at\" >= cast(? as timestamp with time zone)")
                .contains("\"created_at\" <= cast(? as timestamp with time zone)")
                .endsWith("order by \"created_at\"");
        assertThat(sql).contains("\"request_body\"", "\"response_body\"", "\"status_code\"", "\"error_message\"", "\"timeout_ms\"");
        Object[] bindings = executed.get(0).bindings();
        assertThat(bindings).hasSize(3);
        assertThat(bindings[0]).isEqualTo(task.id().toString());
        assertThat(instantOf(bindings[1])).isEqualTo(startedAt.minus(IntegrationTaskLogReader.WINDOW_MARGIN_BEFORE));
        assertThat(instantOf(bindings[2])).isEqualTo(completedAt.plus(IntegrationTaskLogReader.WINDOW_MARGIN_AFTER));
    }

    @Test
    void processingTaskIsReadUpToNowPlusTheMargin() {
        // Arrange
        Instant startedAt = Instant.now().minusSeconds(30);
        IntegrationTaskRow task = task(startedAt, null, IntegrationTaskStatus.PROCESSING);
        Instant before = Instant.now();

        // Act
        reader().findLogs(task);

        // Assert
        Instant after = Instant.now();
        Object[] bindings = executed.get(0).bindings();
        assertThat(instantOf(bindings[1])).isEqualTo(startedAt.minus(IntegrationTaskLogReader.WINDOW_MARGIN_BEFORE));
        assertThat(instantOf(bindings[2]))
                .isBetween(before.plus(IntegrationTaskLogReader.WINDOW_MARGIN_AFTER), after.plus(IntegrationTaskLogReader.WINDOW_MARGIN_AFTER));
    }

    @Test
    void rowsAreMappedToCalls() {
        // Arrange
        Instant createdAt = Instant.parse("2026-11-24T03:00:01Z");
        Instant completedAt = Instant.parse("2026-11-24T03:00:05Z");
        UUID logId = UUID.randomUUID();
        Record row = MOCK_DSL.newRecord(ID, CREATED_AT, COMPLETED_AT, STATUS_CODE, STATUS, REQUEST_BODY, RESPONSE_BODY,
                ERROR_CODE, ERROR_MESSAGE, TIMEOUT_MS);
        row.set(ID, logId);
        row.set(CREATED_AT, utc(createdAt));
        row.set(COMPLETED_AT, utc(completedAt));
        row.set(STATUS_CODE, 200);
        row.set(STATUS, "SUCCESS");
        row.set(REQUEST_BODY, "{\"code\":\"DH1\"}");
        row.set(RESPONSE_BODY, "{\"HeaderId\":1}");
        row.set(TIMEOUT_MS, 300_000L);
        rows.add(row);
        IntegrationTaskRow task = task(createdAt, completedAt, IntegrationTaskStatus.SUCCEEDED);

        // Act
        List<IntegrationTaskLog> logs = reader().findLogs(task);

        // Assert
        assertThat(logs).containsExactly(new IntegrationTaskLog(logId, createdAt, completedAt, 200, "SUCCESS",
                "{\"code\":\"DH1\"}", "{\"HeaderId\":1}", null, null, 300_000L));
    }

    @Test
    void noLoggedCallGivesAnEmptyList() {
        // Arrange
        rows = MOCK_DSL.newResult(List.of(ID));
        IntegrationTaskRow task = task(Instant.now(), Instant.now(), IntegrationTaskStatus.FAILED);

        // Act / Assert
        assertThat(reader().findLogs(task)).isEmpty();
    }
}
