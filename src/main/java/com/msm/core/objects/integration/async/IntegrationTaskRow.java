package com.msm.core.objects.integration.async;

import java.time.Instant;
import java.util.UUID;

// A row of integration_task read by the store; context as JSON text.
public record IntegrationTaskRow(UUID id, String connector, String objectName, UUID recordId, String action,
                                 IntegrationTaskStatus status, String outcome, UUID attemptId, int attemptCount,
                                 String context, String errorCode, String errorMessage, String resolvedBy,
                                 String resolveReason, Instant startedAt, Instant deadlineAt, Instant completedAt,
                                 String createdBy, Instant createdAt, Instant updatedAt, long version) {
}
