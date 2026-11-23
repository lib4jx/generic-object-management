package com.msm.core.objects.integration.async;

import com.msm.core.objects.exception.ObjectErrors;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Table {@code integration_task} of the service (created by the service's own migration, see the README), read and
 * written with jOOQ in the transaction of the calling thread, if any. Every status change out of PROCESSING /
 * UNCONFIRMED is fenced on the status (and on {@code attempt_id} for a call result), so two writers never both win.
 */
@RequiredArgsConstructor
public class IntegrationTaskStore {

    private static final String UNIQUE_VIOLATION = "23505";
    /** Sizes of the README migration: longer values are cut so recording a result never fails on them. */
    static final int CODE_LENGTH = 60;
    static final int MESSAGE_LENGTH = 1000;
    static final int USER_LENGTH = 100;

    private static final Table<Record> TABLE = DSL.table(DSL.name("integration_task"));
    private static final Field<UUID> ID = DSL.field(DSL.name("id"), SQLDataType.UUID);
    private static final Field<String> CONNECTOR = DSL.field(DSL.name("connector"), SQLDataType.VARCHAR);
    private static final Field<String> OBJECT_NAME = DSL.field(DSL.name("object_name"), SQLDataType.VARCHAR);
    private static final Field<UUID> RECORD_ID = DSL.field(DSL.name("record_id"), SQLDataType.UUID);
    private static final Field<String> ACTION = DSL.field(DSL.name("action"), SQLDataType.VARCHAR);
    private static final Field<String> STATUS = DSL.field(DSL.name("status"), SQLDataType.VARCHAR);
    private static final Field<String> OUTCOME = DSL.field(DSL.name("outcome"), SQLDataType.VARCHAR);
    private static final Field<UUID> ATTEMPT_ID = DSL.field(DSL.name("attempt_id"), SQLDataType.UUID);
    private static final Field<Integer> ATTEMPT_COUNT = DSL.field(DSL.name("attempt_count"), SQLDataType.INTEGER);
    private static final Field<JSONB> REQUEST = DSL.field(DSL.name("request"), SQLDataType.JSONB);
    private static final Field<JSONB> CONTEXT = DSL.field(DSL.name("context"), SQLDataType.JSONB);
    private static final Field<JSONB> RESPONSE = DSL.field(DSL.name("response"), SQLDataType.JSONB);
    private static final Field<String> ERROR_CODE = DSL.field(DSL.name("error_code"), SQLDataType.VARCHAR);
    private static final Field<String> ERROR_MESSAGE = DSL.field(DSL.name("error_message"), SQLDataType.VARCHAR);
    private static final Field<String> RESOLVED_BY = DSL.field(DSL.name("resolved_by"), SQLDataType.VARCHAR);
    private static final Field<String> RESOLVE_REASON = DSL.field(DSL.name("resolve_reason"), SQLDataType.VARCHAR);
    private static final Field<OffsetDateTime> STARTED_AT = DSL.field(DSL.name("started_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> DEADLINE_AT = DSL.field(DSL.name("deadline_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> COMPLETED_AT = DSL.field(DSL.name("completed_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    private static final Field<String> CREATED_BY = DSL.field(DSL.name("created_by"), SQLDataType.VARCHAR);
    private static final Field<OffsetDateTime> CREATED_AT = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> UPDATED_AT = DSL.field(DSL.name("updated_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    private static final Field<Long> VERSION = DSL.field(DSL.name("version"), SQLDataType.BIGINT);

    private static final List<Field<?>> ALL_FIELDS = List.of(ID, CONNECTOR, OBJECT_NAME, RECORD_ID, ACTION, STATUS, OUTCOME,
            ATTEMPT_ID, ATTEMPT_COUNT, REQUEST, CONTEXT, RESPONSE, ERROR_CODE, ERROR_MESSAGE, RESOLVED_BY, RESOLVE_REASON,
            STARTED_AT, DEADLINE_AT, COMPLETED_AT, CREATED_BY, CREATED_AT, UPDATED_AT, VERSION);

    private final DSLContext dsl;
    private final IntegrationTaskJson json = new IntegrationTaskJson();

    /**
     * Inserts the task PROCESSING. The record already has a task PROCESSING (unique index
     * {@code uq_integration_task_processing}): {@code integration.task.in_progress}.
     */
    public void insert(NewIntegrationTask task) {
        try {
            dsl.insertInto(TABLE)
                    .columns(ID, CONNECTOR, OBJECT_NAME, RECORD_ID, ACTION, STATUS, ATTEMPT_ID, ATTEMPT_COUNT, REQUEST,
                            CONTEXT, STARTED_AT, DEADLINE_AT, CREATED_BY, CREATED_AT, VERSION)
                    .values(task.id(), task.connector(), task.objectName(), task.recordId(), task.action(),
                            IntegrationTaskStatus.PROCESSING.name(), task.attemptId(), 1, jsonb(task.request()),
                            jsonb(task.context()), time(task.startedAt()), time(task.deadlineAt()),
                            cut(task.createdBy(), USER_LENGTH), time(task.startedAt()), 0L)
                    .execute();
        } catch (org.jooq.exception.DataAccessException | org.springframework.dao.DataAccessException e) {
            throw inProgressOrRethrow(e, task.objectName(), task.recordId());
        }
    }

    public Optional<IntegrationTaskRow> findById(UUID id) {
        return dsl.select(ALL_FIELDS).from(TABLE).where(ID.eq(id)).fetchOptional().map(this::toRow);
    }

    /** A task PROCESSING of one of the records, if any (index {@code uq_integration_task_processing}). */
    public Optional<IntegrationTaskRow> findProcessing(String objectName, Collection<UUID> recordIds) {
        if (recordIds.isEmpty()) {
            return Optional.empty();
        }
        return dsl.select(ALL_FIELDS).from(TABLE)
                .where(OBJECT_NAME.eq(objectName))
                .and(RECORD_ID.in(recordIds))
                .and(STATUS.eq(IntegrationTaskStatus.PROCESSING.name()))
                .limit(1)
                .fetchOptional()
                .map(this::toRow);
    }

    /**
     * Writes the result of the sending {@code attemptId}. 0 rows: the task is no longer PROCESSING under this attempt
     * (the sweeper closed it, or it was sent again), and the result must be dropped.
     */
    public int finish(UUID id, UUID attemptId, ClassifiedOutcome outcome) {
        IntegrationError error = outcome.error();
        Instant now = Instant.now();
        return dsl.update(TABLE)
                .set(STATUS, outcome.status().name())
                .set(OUTCOME, outcome.outcome().name())
                .set(RESPONSE, jsonb(json.write(outcome.response())))
                .set(ERROR_CODE, error == null ? null : cut(error.code(), CODE_LENGTH))
                .set(ERROR_MESSAGE, error == null ? null : cut(error.message(), MESSAGE_LENGTH))
                .set(COMPLETED_AT, time(now))
                .set(UPDATED_AT, time(now))
                .set(VERSION, VERSION.plus(1))
                .where(ID.eq(id))
                .and(ATTEMPT_ID.eq(attemptId))
                .and(STATUS.eq(IntegrationTaskStatus.PROCESSING.name()))
                .execute();
    }

    /**
     * Marks UNCONFIRMED one task PROCESSING past its {@code deadline_at} (database clock) and returns it as it is
     * after the update. Rows locked by another sweeper are skipped and the status is checked again under the lock, so
     * a task is closed by one sweeper only. {@code excluded}: tasks whose callback failed in this round.
     */
    public Optional<IntegrationTaskRow> expireNext(Collection<UUID> excluded, String message) {
        Condition notExcluded = excluded.isEmpty() ? DSL.noCondition() : ID.notIn(excluded);
        Select<Record1<UUID>> next = dsl.select(ID).from(TABLE)
                .where(STATUS.eq(IntegrationTaskStatus.PROCESSING.name()))
                .and(DEADLINE_AT.lt(DSL.currentOffsetDateTime()))
                .and(notExcluded)
                .orderBy(DEADLINE_AT)
                .limit(1)
                .forUpdate()
                .skipLocked();
        Instant now = Instant.now();
        return dsl.update(TABLE)
                .set(STATUS, IntegrationTaskStatus.UNCONFIRMED.name())
                .set(OUTCOME, IntegrationOutcome.DEADLINE_EXCEEDED.name())
                .set(ERROR_CODE, IntegrationOutcome.DEADLINE_EXCEEDED.name())
                .set(ERROR_MESSAGE, cut(message, MESSAGE_LENGTH))
                .set(COMPLETED_AT, time(now))
                .set(UPDATED_AT, time(now))
                .set(VERSION, VERSION.plus(1))
                .where(ID.eq(next))
                .and(STATUS.eq(IntegrationTaskStatus.PROCESSING.name()))
                .returning(ALL_FIELDS)
                .fetchOptional()
                .map(this::toRow);
    }

    /** UNCONFIRMED back to PROCESSING for a new sending (action {@code retry}). 0 rows: no longer UNCONFIRMED. */
    public int restart(UUID id, UUID attemptId, Instant startedAt, Instant deadlineAt, String objectName, UUID recordId) {
        try {
            return dsl.update(TABLE)
                    .set(STATUS, IntegrationTaskStatus.PROCESSING.name())
                    .set(ATTEMPT_ID, attemptId)
                    .set(ATTEMPT_COUNT, ATTEMPT_COUNT.plus(1))
                    .set(STARTED_AT, time(startedAt))
                    .set(DEADLINE_AT, time(deadlineAt))
                    .setNull(OUTCOME)
                    .setNull(RESPONSE)
                    .setNull(ERROR_CODE)
                    .setNull(ERROR_MESSAGE)
                    .setNull(COMPLETED_AT)
                    .set(UPDATED_AT, time(startedAt))
                    .set(VERSION, VERSION.plus(1))
                    .where(ID.eq(id))
                    .and(STATUS.eq(IntegrationTaskStatus.UNCONFIRMED.name()))
                    .execute();
        } catch (org.jooq.exception.DataAccessException | org.springframework.dao.DataAccessException e) {
            throw inProgressOrRethrow(e, objectName, recordId);
        }
    }

    /** UNCONFIRMED closed by hand (action {@code resolve}). 0 rows: no longer UNCONFIRMED. */
    public int resolve(UUID id, IntegrationTaskStatus status, Object data, String reason, String resolvedBy) {
        Instant now = Instant.now();
        boolean failed = status == IntegrationTaskStatus.FAILED;
        return dsl.update(TABLE)
                .set(STATUS, status.name())
                .set(OUTCOME, IntegrationOutcome.RESOLVED.name())
                .set(RESPONSE, jsonb(json.write(data)))
                .set(ERROR_CODE, failed ? IntegrationOutcome.RESOLVED.name() : null)
                .set(ERROR_MESSAGE, failed ? cut(reason, MESSAGE_LENGTH) : null)
                .set(RESOLVED_BY, cut(resolvedBy, USER_LENGTH))
                .set(RESOLVE_REASON, cut(reason, MESSAGE_LENGTH))
                .set(COMPLETED_AT, time(now))
                .set(UPDATED_AT, time(now))
                .set(VERSION, VERSION.plus(1))
                .where(ID.eq(id))
                .and(STATUS.eq(IntegrationTaskStatus.UNCONFIRMED.name()))
                .execute();
    }

    private RuntimeException inProgressOrRethrow(RuntimeException e, String objectName, UUID recordId) {
        return isUniqueViolation(e) ? ObjectErrors.integrationTaskInProgress(objectName, recordId, e) : e;
    }

    private boolean isUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof DuplicateKeyException) {
                return true;
            }
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private IntegrationTaskRow toRow(Record record) {
        return new IntegrationTaskRow(
                record.get(ID),
                record.get(CONNECTOR),
                record.get(OBJECT_NAME),
                record.get(RECORD_ID),
                record.get(ACTION),
                IntegrationTaskStatus.valueOf(record.get(STATUS)),
                record.get(OUTCOME),
                record.get(ATTEMPT_ID),
                record.get(ATTEMPT_COUNT),
                text(record.get(REQUEST)),
                text(record.get(CONTEXT)),
                text(record.get(RESPONSE)),
                record.get(ERROR_CODE),
                record.get(ERROR_MESSAGE),
                record.get(RESOLVED_BY),
                record.get(RESOLVE_REASON),
                instant(record.get(STARTED_AT)),
                instant(record.get(DEADLINE_AT)),
                instant(record.get(COMPLETED_AT)),
                record.get(CREATED_BY),
                instant(record.get(CREATED_AT)),
                instant(record.get(UPDATED_AT)),
                record.get(VERSION));
    }

    private JSONB jsonb(String json) {
        return json == null ? null : JSONB.valueOf(json);
    }

    private String text(JSONB value) {
        return value == null ? null : value.data();
    }

    private String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    private OffsetDateTime time(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
