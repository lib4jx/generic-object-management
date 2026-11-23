package com.msm.core.objects.entity.integration;

import com.msm.core.security.annotations.IgnorePermission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One call to a partner made by {@code AsyncIntegrationDispatcher} with {@code async.enabled}. Read through the
 * generic routes (object {@code integrationtask}); written only by the library (store, worker, sweeper, actions
 * {@code retry} / {@code resolve}). Table created by the service's own migration (README). No soft delete: a log.
 */
@IgnorePermission
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "integration_task")
public class IntegrationTask {

    @Id
    @NotNull
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "connector", nullable = false)
    private String connector;

    @Column(name = "object_name", nullable = false)
    private String objectName;

    @Column(name = "record_id", nullable = false)
    private UUID recordId;

    @Column(name = "action", nullable = false)
    private String action;

    /** PROCESSING / SUCCEEDED / FAILED / UNCONFIRMED. */
    @Column(name = "status", nullable = false)
    private String status;

    /** How the result was classified, e.g. ALREADY_DONE. */
    @Column(name = "outcome")
    private String outcome;

    /** Changes at every sending: a result of an older sending cannot overwrite the task. */
    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    /** Payload sent to the partner, sent again as is by {@code retry}. */
    @Column(name = "request")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> request;

    /** Data of the action the result step needs (target status of a cancel...). */
    @Column(name = "context")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> context;

    @Column(name = "response")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> response;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolve_reason")
    private String resolveReason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "version", nullable = false)
    private Long version;
}
