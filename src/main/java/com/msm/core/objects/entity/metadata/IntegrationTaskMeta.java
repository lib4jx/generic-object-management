package com.msm.core.objects.entity.metadata;

import com.msm.core.metadata.typesafe.TypedAttribute;
import org.jooq.JSONB;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.time.Instant;
import java.util.UUID;

import static com.msm.core.metadata.typesafe.MetaFieldBuilder.attr;

public final class IntegrationTaskMeta {

    private IntegrationTaskMeta() {}

    public static final String OBJECT_NAME = "integrationtask";

    // Actions of the object (PUT /integrationtask/{id}/{action})
    public static final String ACTION_RETRY = "retry";
    public static final String ACTION_RESOLVE = "resolve";

    public static final Table<?> TABLE =
            DSL.table(DSL.name("integration_task"));

    public static final TypedAttribute<UUID> ID =
            attr(TABLE, "id", "id", UUID.class);

    public static final TypedAttribute<String> CONNECTOR =
            attr(TABLE, "connector", "connector", String.class);

    public static final TypedAttribute<String> OBJECT_NAME_FIELD =
            attr(TABLE, "objectName", "object_name", String.class);

    public static final TypedAttribute<UUID> RECORD_ID =
            attr(TABLE, "recordId", "record_id", UUID.class);

    public static final TypedAttribute<String> ACTION =
            attr(TABLE, "action", "action", String.class);

    public static final TypedAttribute<String> STATUS =
            attr(TABLE, "status", "status", String.class);

    public static final TypedAttribute<String> OUTCOME =
            attr(TABLE, "outcome", "outcome", String.class);

    public static final TypedAttribute<UUID> ATTEMPT_ID =
            attr(TABLE, "attemptId", "attempt_id", UUID.class);

    public static final TypedAttribute<Integer> ATTEMPT_COUNT =
            attr(TABLE, "attemptCount", "attempt_count", Integer.class);

    public static final TypedAttribute<JSONB> REQUEST =
            attr(TABLE, "request", "request", JSONB.class);

    public static final TypedAttribute<JSONB> CONTEXT =
            attr(TABLE, "context", "context", JSONB.class);

    public static final TypedAttribute<JSONB> RESPONSE =
            attr(TABLE, "response", "response", JSONB.class);

    public static final TypedAttribute<String> ERROR_CODE =
            attr(TABLE, "errorCode", "error_code", String.class);

    public static final TypedAttribute<String> ERROR_MESSAGE =
            attr(TABLE, "errorMessage", "error_message", String.class);

    public static final TypedAttribute<String> RESOLVED_BY =
            attr(TABLE, "resolvedBy", "resolved_by", String.class);

    public static final TypedAttribute<String> RESOLVE_REASON =
            attr(TABLE, "resolveReason", "resolve_reason", String.class);

    public static final TypedAttribute<Instant> STARTED_AT =
            attr(TABLE, "startedAt", "started_at", Instant.class);

    public static final TypedAttribute<Instant> DEADLINE_AT =
            attr(TABLE, "deadlineAt", "deadline_at", Instant.class);

    public static final TypedAttribute<Instant> COMPLETED_AT =
            attr(TABLE, "completedAt", "completed_at", Instant.class);

    public static final TypedAttribute<String> CREATED_BY =
            attr(TABLE, "createdBy", "created_by", String.class);

    public static final TypedAttribute<Instant> CREATED_AT =
            attr(TABLE, "createdAt", "created_at", Instant.class);

    public static final TypedAttribute<Instant> UPDATED_AT =
            attr(TABLE, "updatedAt", "updated_at", Instant.class);

    public static final TypedAttribute<Long> VERSION =
            attr(TABLE, "version", "version", Long.class);
}
