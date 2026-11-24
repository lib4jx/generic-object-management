package com.msm.core.objects.integration.async;

import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The calls of a task in {@code integration_log} ({@code correlation_id} = id of the task, written by
 * {@code TracingMiddleware}), oldest first. The query bounds {@code created_at} to the task's time window (a margin
 * either side for clock skew and the time between the task row and the call), so Postgres scans only the partitions of
 * that window of the partitioned {@code integration_log}, through the index on {@code correlation_id}.
 *
 * <p>{@code integration_log} is written asynchronously, best effort, after each call ends: a call may be missing (log
 * write failed) or not there yet (task PROCESSING, or just finished). Use it to view / investigate what was sent and
 * answered, never to run a call again or to decide a task's result.
 */
@RequiredArgsConstructor
public class IntegrationTaskLogReader {

    /** Margin before {@code started_at} of the task on {@code integration_log.created_at}. */
    static final Duration WINDOW_MARGIN_BEFORE = Duration.ofSeconds(5);
    /**
     * Margin after {@code completed_at} (or now) of the task: {@code integration_log.created_at} is the time the row is
     * inserted, asynchronously after the call ended, so a busy service can write it seconds later.
     */
    static final Duration WINDOW_MARGIN_AFTER = Duration.ofMinutes(1);

    private static final Table<Record> TABLE = DSL.table(DSL.name("integration_log"));
    private static final Field<UUID> ID = DSL.field(DSL.name("id"), SQLDataType.UUID);
    private static final Field<OffsetDateTime> CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> COMPLETED_AT = DSL.field(DSL.name("completed_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    private static final Field<String> CORRELATION_ID = DSL.field(DSL.name("correlation_id"), SQLDataType.VARCHAR);
    private static final Field<Integer> STATUS_CODE = DSL.field(DSL.name("status_code"), SQLDataType.INTEGER);
    private static final Field<String> STATUS = DSL.field(DSL.name("status"), SQLDataType.VARCHAR);
    private static final Field<String> REQUEST_BODY = DSL.field(DSL.name("request_body"), SQLDataType.CLOB);
    private static final Field<String> RESPONSE_BODY = DSL.field(DSL.name("response_body"), SQLDataType.CLOB);
    private static final Field<String> ERROR_CODE = DSL.field(DSL.name("error_code"), SQLDataType.VARCHAR);
    private static final Field<String> ERROR_MESSAGE = DSL.field(DSL.name("error_message"), SQLDataType.CLOB);
    private static final Field<Long> TIMEOUT_MS = DSL.field(DSL.name("timeout_ms"), SQLDataType.BIGINT);

    private static final List<Field<?>> FIELDS = List.of(ID, CREATED_AT, COMPLETED_AT, STATUS_CODE, STATUS, REQUEST_BODY,
            RESPONSE_BODY, ERROR_CODE, ERROR_MESSAGE, TIMEOUT_MS);

    private final DSLContext dsl;

    /** The calls of the task (e.g. read with {@link IntegrationTaskStore#findById}), oldest first; empty if none logged. */
    public List<IntegrationTaskLog> findLogs(IntegrationTaskRow task) {
        return logsQuery(task).fetch(this::toLog);
    }

    /** The query of {@link #findLogs}; package-private for the plan check of the Postgres test. */
    ResultQuery<Record> logsQuery(IntegrationTaskRow task) {
        return dsl.select(FIELDS).from(TABLE)
                .where(condition(task))
                .orderBy(CREATED_AT);
    }

    /**
     * {@code correlation_id = <task id> and created_at >= started_at - 5 s
     * and created_at <= coalesce(completed_at, now) + 1 min}.
     */
    private Condition condition(IntegrationTaskRow task) {
        Instant from = task.startedAt().minus(WINDOW_MARGIN_BEFORE);
        Instant end = task.completedAt() != null ? task.completedAt() : Instant.now();
        return CORRELATION_ID.eq(task.id().toString())
                .and(CREATED_AT.ge(time(from)))
                .and(CREATED_AT.le(time(end.plus(WINDOW_MARGIN_AFTER))));
    }

    private IntegrationTaskLog toLog(Record record) {
        return new IntegrationTaskLog(
                record.get(ID),
                instant(record.get(CREATED_AT)),
                instant(record.get(COMPLETED_AT)),
                record.get(STATUS_CODE),
                record.get(STATUS),
                record.get(REQUEST_BODY),
                record.get(RESPONSE_BODY),
                record.get(ERROR_CODE),
                record.get(ERROR_MESSAGE),
                record.get(TIMEOUT_MS));
    }

    private OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
